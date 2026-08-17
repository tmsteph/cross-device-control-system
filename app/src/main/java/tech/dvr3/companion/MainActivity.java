package tech.dvr3.companion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity implements UpdateManager.Listener {
    private TextView status;
    private TextView tokenView;
    private TextView updateStatus;
    private Button installUpdateButton;
    private UpdateManager updateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        updateManager = new UpdateManager(this, this);

        int pad = dp(24);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("3DVR Companion");
        title.setTextSize(28);
        body.addView(title);

        TextView version = new TextView(this);
        version.setText("Build " + currentVersionCode());
        version.setTextSize(13);
        body.addView(version);

        TextView intro = new TextView(this);
        intro.setText("Open-source Android agent control. Enable the accessibility service, then 3DVR Companion can inspect UI structure and perform user-authorized taps, typing, scrolling, and navigation.");
        intro.setTextSize(16);
        intro.setPadding(0, dp(16), 0, dp(16));
        body.addView(intro);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, 0, 0, dp(12));
        body.addView(status);

        updateStatus = new TextView(this);
        updateStatus.setText("Update: checking…");
        updateStatus.setTextSize(14);
        updateStatus.setPadding(0, 0, 0, dp(8));
        body.addView(updateStatus);

        Button checkUpdateButton = new Button(this);
        checkUpdateButton.setText("Check for Updates");
        checkUpdateButton.setOnClickListener(v -> updateManager.checkForUpdates(true));
        body.addView(checkUpdateButton, matchWidth());

        installUpdateButton = new Button(this);
        installUpdateButton.setText("Install Update");
        installUpdateButton.setVisibility(View.GONE);
        installUpdateButton.setOnClickListener(v -> updateManager.downloadAndInstall());
        body.addView(installUpdateButton, matchWidth());

        tokenView = new TextView(this);
        tokenView.setTextSize(13);
        tokenView.setPadding(0, dp(16), 0, dp(16));
        body.addView(tokenView);

        Button settingsButton = new Button(this);
        settingsButton.setText("Open Accessibility Settings");
        settingsButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        body.addView(settingsButton, matchWidth());

        Button snapshotButton = new Button(this);
        snapshotButton.setText("Copy Current UI Snapshot");
        snapshotButton.setOnClickListener(v -> copySnapshot());
        body.addView(snapshotButton, matchWidth());

        Button tokenButton = new Button(this);
        tokenButton.setText("Copy Local Bridge Token");
        tokenButton.setOnClickListener(v -> copyText("3DVR bridge token", AgentTokenStore.getOrCreate(this), "Token copied."));
        body.addView(tokenButton, matchWidth());

        Button termuxButton = new Button(this);
        termuxButton.setText("Copy Termux Snapshot Command");
        termuxButton.setOnClickListener(v -> copyTermuxCommand());
        body.addView(termuxButton, matchWidth());

        TextView note = new TextView(this);
        note.setText("Local bridge: 127.0.0.1:8765 only. Updates are downloaded from this project's GitHub Releases and rejected unless the package name, version, and signing identity match the installed app.");
        note.setTextSize(14);
        note.setPadding(0, dp(20), 0, 0);
        body.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);

        updateManager.checkForUpdates(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public void onStatus(String message) {
        updateStatus.setText("Update: " + message);
    }

    @Override
    public void onUpdateAvailable(long versionCode, String versionName) {
        updateStatus.setText("Update available: " + versionName + " (build " + versionCode + ")");
        installUpdateButton.setText("Install Update " + versionName);
        installUpdateButton.setVisibility(View.VISIBLE);
    }

    @Override
    public void onNoUpdate(long currentVersionCode) {
        updateStatus.setText("Update: current (build " + currentVersionCode + ")");
        installUpdateButton.setVisibility(View.GONE);
    }

    @Override
    public void onError(String message) {
        updateStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void refreshStatus() {
        boolean connected = AgentAccessibilityService.getInstance() != null;
        status.setText(connected
                ? "Agent service: CONNECTED • local bridge active"
                : "Agent service: NOT CONNECTED");
        tokenView.setText("Local token: " + AgentTokenStore.getOrCreate(this));
    }

    private long currentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    private void copySnapshot() {
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        if (service == null) {
            Toast.makeText(this, "Enable 3DVR Companion in Accessibility settings first.", Toast.LENGTH_LONG).show();
            return;
        }
        copyText("3DVR UI snapshot", service.snapshotUi(), "UI snapshot copied.");
    }

    private void copyTermuxCommand() {
        String token = AgentTokenStore.getOrCreate(this);
        String command = "curl -s http://127.0.0.1:" + LocalAgentServer.PORT
                + "/command -H 'Authorization: Bearer " + token
                + "' -H 'Content-Type: application/json' -d '{\"command\":\"snapshot\"}'";
        copyText("3DVR Termux command", command, "Termux snapshot command copied.");
    }

    private void copyText(String label, String text, String toast) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
