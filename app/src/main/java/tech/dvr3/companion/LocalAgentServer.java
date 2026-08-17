package tech.dvr3.companion;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
    private static final int MAX_BODY_BYTES = 65_536;
    private static final int MAX_HEADER_LINE_BYTES = 8_192;

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
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {

            socket.setSoTimeout(5_000);
            String requestLine = readAsciiLine(input);
            if (requestLine == null || requestLine.isBlank()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeJson(output, 400, error("bad_request"));
                return;
            }

            String method = parts[0];
            String path = parts[1];
            Map<String, String> headers = readHeaders(input);

            if ("GET".equals(method) && "/health".equals(path)) {
                JSONObject health = new JSONObject();
                health.put("ok", true);
                health.put("serviceConnected", AgentAccessibilityService.getInstance() != null);
                health.put("bind", "127.0.0.1:" + PORT);
                writeJson(output, 200, health);
                return;
            }

            if (!"POST".equals(method) || !"/command".equals(path)) {
                writeJson(output, 404, error("not_found"));
                return;
            }

            String expected = "Bearer " + AgentTokenStore.getOrCreate(service);
            if (!expected.equals(headers.get("authorization"))) {
                writeJson(output, 401, error("unauthorized"));
                return;
            }

            int length = parseContentLength(headers.get("content-length"));
            if (length < 0 || length > MAX_BODY_BYTES) {
                writeJson(output, 413, error("invalid_body_length"));
                return;
            }

            byte[] body = input.readNBytes(length);
            if (body.length != length) {
                writeJson(output, 400, error("incomplete_body"));
                return;
            }

            JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONObject result = executeOnMain(request);
            int status = result.optBoolean("ok", false) ? 200 : 400;
            writeJson(output, status, result);
        } catch (Exception e) {
            Log.e(TAG, "Client request failed", e);
        }
    }

    private Map<String, String> readHeaders(BufferedInputStream input) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readAsciiLine(input)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }
        return headers;
    }

    private String readAsciiLine(BufferedInputStream input) throws IOException {
        byte[] buffer = new byte[MAX_HEADER_LINE_BYTES];
        int count = 0;
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') break;
            if (next == '\r') continue;
            if (count >= buffer.length) throw new IOException("HTTP header line too long");
            buffer[count++] = (byte) next;
        }
        if (next == -1 && count == 0) return null;
        return new String(buffer, 0, count, StandardCharsets.US_ASCII);
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

    private void writeJson(OutputStream output, int status, JSONObject body) throws IOException {
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String reason = status >= 200 && status < 300 ? "OK" : "Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(bodyBytes);
        output.flush();
    }
}
