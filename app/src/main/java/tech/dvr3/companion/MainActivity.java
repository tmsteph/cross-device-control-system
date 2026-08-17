package tech.dvr3.companion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(24);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("3DVR Companion");
        title.setTextSize(28);
        body.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Open-source Android agent control. Enable the accessibility service, then 3DVR Companion can inspect UI structure and perform user-authorized taps, typing, scrolling, and navigation.");
        intro.setTextSize(16);
        intro.setPadding(0, dp(16), 0, dp(16));
        body.addView(intro);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, 0, 0, dp(16));
        body.addView(status);

        Button settingsButton = new Button(this);
        settingsButton.setText("Open Accessibility Settings");
        settingsButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        body.addView(settingsButton, matchWidth());

        Button snapshotButton = new Button(this);
        snapshotButton.setText("Copy Current UI Snapshot");
        snapshotButton.setOnClickListener(v -> copySnapshot());
        body.addView(snapshotButton, matchWidth());

        TextView note = new TextView(this);
        note.setText("MVP boundary: no remote listener yet. The first milestone proves local UI inspection and control before we add authenticated agent transport.");
        note.setTextSize(14);
        note.setPadding(0, dp(20), 0, 0);
        body.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean connected = AgentAccessibilityService.getInstance() != null;
        status.setText(connected ? "Agent service: CONNECTED" : "Agent service: NOT CONNECTED");
    }

    private void copySnapshot() {
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        if (service == null) {
            Toast.makeText(this, "Enable 3DVR Companion in Accessibility settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        String snapshot = service.snapshotUi();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("3DVR UI snapshot", snapshot));
        Toast.makeText(this, "UI snapshot copied.", Toast.LENGTH_SHORT).show();
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
