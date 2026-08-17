package tech.dvr3.companion;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Secure updater backed by the repository's latest GitHub Release. */
final class UpdateManager {
    interface Listener {
        void onStatus(String message);
        void onUpdateAvailable(long versionCode, String versionName);
        void onNoUpdate(long currentVersionCode);
        void onError(String message);
    }

    private static final String RELEASE_API =
            "https://api.github.com/repos/tmsteph/cross-device-control-system/releases/latest";
    private static final String APK_ASSET = "3dvr-companion.apk";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long MAX_APK_BYTES = 100L * 1024L * 1024L;

    private final Activity activity;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile ReleaseInfo availableRelease;
    private volatile File downloadedApk;

    UpdateManager(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    void checkForUpdates(boolean userInitiated) {
        if (userInitiated) postStatus("Checking for updates…");
        executor.execute(() -> {
            try {
                long current = currentVersionCode();
                ReleaseInfo release = fetchLatestRelease();
                if (release.versionCode > current) {
                    availableRelease = release;
                    activity.runOnUiThread(() -> listener.onUpdateAvailable(
                            release.versionCode, release.versionName));
                } else {
                    availableRelease = null;
                    activity.runOnUiThread(() -> listener.onNoUpdate(current));
                }
            } catch (Exception e) {
                if (userInitiated) postError("Update check failed: " + safeMessage(e));
            }
        });
    }

    void downloadAndInstall() {
        ReleaseInfo release = availableRelease;
        if (release == null) {
            postError("No newer release is currently available.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            postStatus("Enable “Allow from this source”, then tap Install Update again.");
            return;
        }

        postStatus("Downloading update…");
        executor.execute(() -> {
            try {
                File apk = downloadRelease(release);
                verifyCandidate(apk, release.versionCode);
                downloadedApk = apk;
                postStatus("Update verified. Opening Android installer…");
                installPackage(apk);
            } catch (Exception e) {
                postError("Update failed: " + safeMessage(e));
            }
        });
    }

    private ReleaseInfo fetchLatestRelease() throws Exception {
        HttpURLConnection connection = open(RELEASE_API);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        int status = connection.getResponseCode();
        if (status != 200) throw new IllegalStateException("GitHub returned HTTP " + status);

        String json;
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }

        JSONObject root = new JSONObject(json);
        String tag = root.getString("tag_name");
        long versionCode = parseVersionCode(tag);
        JSONArray assets = root.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!APK_ASSET.equals(asset.optString("name"))) continue;
            String url = asset.getString("browser_download_url");
            long size = asset.optLong("size", -1L);
            if (size <= 0 || size > MAX_APK_BYTES) {
                throw new IllegalStateException("Release APK has an invalid size");
            }
            return new ReleaseInfo(versionCode, tag, url, size);
        }
        throw new IllegalStateException("Latest release has no " + APK_ASSET + " asset");
    }

    private File downloadRelease(ReleaseInfo release) throws Exception {
        File dir = new File(activity.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create update cache");
        }
        File target = new File(dir, APK_ASSET);

        HttpURLConnection connection = open(release.downloadUrl);
        connection.setInstanceFollowRedirects(true);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("APK download returned HTTP " + status);
        }

        long contentLength = connection.getContentLengthLong();
        if (contentLength > MAX_APK_BYTES) {
            connection.disconnect();
            throw new IllegalStateException("APK is unexpectedly large");
        }

        long total = 0;
        byte[] buffer = new byte[32 * 1024];
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             OutputStream output = new FileOutputStream(target, false)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_APK_BYTES) throw new IllegalStateException("APK exceeded size limit");
                output.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }

        if (total != release.assetSize) {
            throw new IllegalStateException("APK download size did not match the release asset");
        }
        return target;
    }

    private void verifyCandidate(File apk, long expectedVersionCode) throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInfo current = pm.getPackageInfo(
                activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo candidate = pm.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);

        if (candidate == null || !activity.getPackageName().equals(candidate.packageName)) {
            throw new SecurityException("Downloaded APK is not 3DVR Companion");
        }

        long candidateVersion = packageVersionCode(candidate);
        if (candidateVersion != expectedVersionCode || candidateVersion <= packageVersionCode(current)) {
            throw new SecurityException("Downloaded APK version is not a valid upgrade");
        }

        Signature[] currentSigners = signers(current);
        Signature[] candidateSigners = signers(candidate);
        if (currentSigners.length == 0 || candidateSigners.length == 0
                || currentSigners.length != candidateSigners.length) {
            throw new SecurityException("Could not verify APK signing identity");
        }

        byte[][] currentBytes = signatureBytes(currentSigners);
        byte[][] candidateBytes = signatureBytes(candidateSigners);
        Arrays.sort(currentBytes, UpdateManager::compareBytes);
        Arrays.sort(candidateBytes, UpdateManager::compareBytes);
        for (int i = 0; i < currentBytes.length; i++) {
            if (!Arrays.equals(currentBytes[i], candidateBytes[i])) {
                throw new SecurityException("Update signing key does not match installed app");
            }
        }
    }

    private void installPackage(File apk) throws Exception {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());

        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             InputStream input = new BufferedInputStream(new java.io.FileInputStream(apk));
             OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            session.fsync(output);

            Intent statusIntent = new Intent(activity, UpdateInstallReceiver.class)
                    .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    activity,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pendingIntent.getIntentSender());
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "3DVR-Companion-Updater");
        return connection;
    }

    private long currentVersionCode() throws Exception {
        return packageVersionCode(activity.getPackageManager().getPackageInfo(
                activity.getPackageName(), 0));
    }

    private long packageVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
    }

    private Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private byte[][] signatureBytes(Signature[] signatures) {
        byte[][] out = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; i++) out[i] = signatures[i].toByteArray();
        return out;
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int ai = a[i] & 0xff;
            int bi = b[i] & 0xff;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return Integer.compare(a.length, b.length);
    }

    private long parseVersionCode(String tag) {
        String normalized = tag == null ? "" : tag.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("[0-9]+")) {
            throw new IllegalArgumentException("Release tag is not a numeric version: " + tag);
        }
        return Long.parseLong(normalized);
    }

    private void postStatus(String message) {
        activity.runOnUiThread(() -> listener.onStatus(message));
    }

    private void postError(String message) {
        activity.runOnUiThread(() -> listener.onError(message));
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static final class ReleaseInfo {
        final long versionCode;
        final String versionName;
        final String downloadUrl;
        final long assetSize;

        ReleaseInfo(long versionCode, String versionName, String downloadUrl, long assetSize) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.downloadUrl = downloadUrl;
            this.assetSize = assetSize;
        }
    }
}
