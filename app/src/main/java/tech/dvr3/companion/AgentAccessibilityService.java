package tech.dvr3.companion;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Core on-device automation primitive for 3DVR Companion.
 *
 * The user must explicitly enable this AccessibilityService in Android settings.
 * Once enabled, higher-level agent code can inspect the active UI tree and invoke
 * taps, typing, scrolling, and system navigation through this class.
 */
public final class AgentAccessibilityService extends AccessibilityService {
    private static final String TAG = "3DVRAgent";
    private static volatile AgentAccessibilityService instance;

    private LocalAgentServer localServer;

    public static AgentAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        localServer = new LocalAgentServer(this);
        localServer.start();
        Log.i(TAG, "3DVR Companion accessibility agent connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Active window: " + event.getPackageName());
        }
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "Accessibility agent interrupted");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (localServer != null) {
            localServer.stop();
            localServer = null;
        }
        instance = null;
        return super.onUnbind(intent);
    }

    public boolean tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    public boolean swipe(float startX, float startY, float endX, float endY, long durationMs) {
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(100, durationMs)))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    public boolean clickText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || text == null || text.isBlank()) return false;

        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : matches) {
            if (clickNodeOrParent(node)) return true;
        }
        return false;
    }

    public boolean clickViewId(String viewId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || viewId == null || viewId.isBlank()) return false;

        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(viewId);
        for (AccessibilityNodeInfo node : matches) {
            if (clickNodeOrParent(node)) return true;
        }
        return false;
    }

    public boolean setTextByViewId(String viewId, String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || viewId == null || viewId.isBlank()) return false;

        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(viewId);
        for (AccessibilityNodeInfo node : matches) {
            if (setText(node, value)) return true;
        }
        return false;
    }

    public boolean setTextOnFocusedField(String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return focused != null && setText(focused, value);
    }

    public boolean scrollForward() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo scrollable = findScrollable(root);
        return scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    public boolean scrollBackward() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo scrollable = findScrollable(root);
        return scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    public boolean back() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public boolean home() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public boolean recents() {
        return performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    public boolean notifications() {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    /** Returns a compact JSON snapshot suitable for an agent planner. */
    public String snapshotUi() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "{\"error\":\"no_active_window\"}";
        }

        try {
            JSONObject result = new JSONObject();
            result.put("package", safe(root.getPackageName()));
            result.put("root", nodeToJson(root, 0));
            return result.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize UI tree", e);
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.isClickable() && current.isEnabled()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean setText(AccessibilityNodeInfo node, String value) {
        if (node == null || !node.isEnabled()) return false;
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value == null ? "" : value
        );
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo node, int depth) throws JSONException {
        JSONObject out = new JSONObject();
        if (node == null || depth > 40) return out;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        putIfPresent(out, "text", node.getText());
        putIfPresent(out, "description", node.getContentDescription());
        putIfPresent(out, "viewId", node.getViewIdResourceName());
        putIfPresent(out, "class", node.getClassName());

        out.put("bounds", new JSONArray()
                .put(bounds.left)
                .put(bounds.top)
                .put(bounds.right)
                .put(bounds.bottom));
        out.put("clickable", node.isClickable());
        out.put("editable", node.isEditable());
        out.put("enabled", node.isEnabled());
        out.put("focusable", node.isFocusable());
        out.put("scrollable", node.isScrollable());

        JSONArray children = new JSONArray();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) children.put(nodeToJson(child, depth + 1));
        }
        if (children.length() > 0) out.put("children", children);
        return out;
    }

    private void putIfPresent(JSONObject out, String key, CharSequence value) throws JSONException {
        String text = safe(value);
        if (!text.isEmpty()) out.put(key, text);
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
