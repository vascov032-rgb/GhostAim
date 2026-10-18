package com.ghost.app;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

public class GhostAccessibilityService extends AccessibilityService {
    public static GhostAccessibilityService instance;
    @Override public void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    public void swipeAim(float startX, float startY, float endX, float endY) {
        if (instance == null) return;
        Path path = new Path(); 
        path.moveTo(startX, startY); 
        path.lineTo(endX, endY);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 80));
        dispatchGesture(builder.build(), null, null);
    }
}
