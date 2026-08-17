package tech.dvr3.companion;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal authenticated HTTP/JSON bridge bound only to Android loopback.
 * This is intentionally not reachable over Wi-Fi/cellular interfaces.
 */
final class LocalAgentServer {
    private static final String TAG = "3DVRLocalBridge";
    static final int PORT = 8765;
    private static final int MAX_BODY_CHARS = 65_536;

    private final AgentAccessibilityService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService clients = Executors.newCachedThreadPool();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    LocalAgentServer(AgentAccessibilityService service) {
        this.service = service;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "3dvr-loopback-accept");
        acceptThread.start();
    }

    synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        clients.shutdownNow();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
            Log.i(TAG, "Listening on 127.0.0.1:" + PORT);

            while (running) {
                Socket socket = serverSocket.accept();
                clients.execute(() -> handle(socket));
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "Loopback server stopped unexpectedly", e);
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            socket.setSoTimeout(5_000);
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isBlank()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeJson(writer, 400, error("bad_request"));
                return;
            }

            String method = parts[0];
            String path = parts[1];
            Map<String, String> headers = readHeaders(reader);

            if ("GET".equals(method) && "/health".equals(path)) {
                JSONObject health = new JSONObject();
                health.put("ok", true);
                health.put("serviceConnected", AgentAccessibilityService.getInstance() != null);
                health.put("bind", "127.0.0.1:" + PORT);
                writeJson(writer, 200, health);
                return;
            }

            if (!"POST".equals(method) || !"/command".equals(path)) {
                writeJson(writer, 404, error("not_found"));
                return;
            }

            String expected = "Bearer " + AgentTokenStore.getOrCreate(service);
            if (!expected.equals(headers.get("authorization"))) {
                writeJson(writer, 401, error("unauthorized"));
                return;
            }

            int length = parseContentLength(headers.get("content-length"));
            if (length < 0 || length > MAX_BODY_CHARS) {
                writeJson(writer, 413, error("invalid_body_length"));
                return;
            }

            char[] body = new char[length];
            int offset = 0;
            while (offset < length) {
                int count = reader.read(body, offset, length - offset);
                if (count < 0) break;
                offset += count;
            }

            if (offset != length) {
                writeJson(writer, 400, error("incomplete_body"));
                return;
            }

            JSONObject request = new JSONObject(new String(body));
            JSONObject result = executeOnMain(request);
            int status = result.optBoolean("ok", false) ? 200 : 400;
            writeJson(writer, status, result);
        } catch (Exception e) {
            Log.e(TAG, "Client request failed", e);
        }
    }

    private Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }
        return headers;
    }

    private int parseContentLength(String raw) {
        if (raw == null) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private JSONObject executeOnMain(JSONObject request) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                result.set(execute(request));
            } catch (Exception e) {
                Log.e(TAG, "Command failed", e);
                result.set(error("command_failed"));
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(5, TimeUnit.SECONDS)) return error("command_timeout");
        return result.get() == null ? error("empty_result") : result.get();
    }

    private JSONObject execute(JSONObject request) throws JSONException {
        String command = request.optString("command", "");
        JSONObject response = new JSONObject();
        boolean actionResult;

        switch (command) {
            case "snapshot":
                response.put("ok", true);
                response.put("snapshot", new JSONObject(service.snapshotUi()));
                return response;
            case "tap":
                actionResult = service.tap((float) request.getDouble("x"), (float) request.getDouble("y"));
                break;
            case "swipe":
                actionResult = service.swipe(
                        (float) request.getDouble("startX"),
                        (float) request.getDouble("startY"),
                        (float) request.getDouble("endX"),
                        (float) request.getDouble("endY"),
                        request.optLong("durationMs", 300)
                );
                break;
            case "clickText":
                actionResult = service.clickText(request.getString("text"));
                break;
            case "clickId":
                actionResult = service.clickViewId(request.getString("viewId"));
                break;
            case "typeFocused":
                actionResult = service.setTextOnFocusedField(request.optString("value", ""));
                break;
            case "typeId":
                actionResult = service.setTextByViewId(
                        request.getString("viewId"),
                        request.optString("value", "")
                );
                break;
            case "scrollForward":
                actionResult = service.scrollForward();
                break;
            case "scrollBackward":
                actionResult = service.scrollBackward();
                break;
            case "back":
                actionResult = service.back();
                break;
            case "home":
                actionResult = service.home();
                break;
            case "recents":
                actionResult = service.recents();
                break;
            case "notifications":
                actionResult = service.notifications();
                break;
            default:
                return error("unknown_command");
        }

        response.put("ok", actionResult);
        response.put("command", command);
        if (!actionResult) response.put("error", "action_rejected_or_target_not_found");
        return response;
    }

    private JSONObject error(String code) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", code);
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void writeJson(BufferedWriter writer, int status, JSONObject body) throws IOException {
        String json = body.toString();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String reason = status >= 200 && status < 300 ? "OK" : "Error";
        writer.write("HTTP/1.1 " + status + " " + reason + "\r\n");
        writer.write("Content-Type: application/json; charset=utf-8\r\n");
        writer.write("Content-Length: " + bytes.length + "\r\n");
        writer.write("Connection: close\r\n\r\n");
        writer.write(json);
        writer.flush();
    }
}
