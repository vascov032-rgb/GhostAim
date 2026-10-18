package com.ghost.app;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import rikka.shizuku.Shizuku;
import rikka.shizuku.SystemServiceHelper;
import rikka.shizuku.ShizukuBinderWrapper;

public class ShizukuInputInjector {
    private static Object inputManager;
    private static Method injectMethod;

    public static boolean init() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0);
                return false;
            }
            IBinder binder = SystemServiceHelper.getSystemService("input");
            Object wrapper = new ShizukuBinderWrapper(binder);
            Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
            inputManager = stubClass.getMethod("asInterface", IBinder.class).invoke(null, wrapper);
            Class<?> iimClass = Class.forName("android.hardware.input.IInputManager");
            for (Method m : iimClass.getMethods()) {
                if (m.getName().equals("injectInputEvent") && m.getParameterTypes().length == 2) {
                    injectMethod = m;
                    break;
                }
            }
            if (injectMethod == null) {
                for (Method m : iimClass.getDeclaredMethods()) {
                    if (m.getName().equals("injectInputEvent")) {
                        injectMethod = m;
                        break;
                    }
                }
            }
            if (injectMethod != null) {
                injectMethod.setAccessible(true);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static void swipe(float startX, float startY, float endX, float endY, long durationMs) {
        if (inputManager == null || injectMethod == null) return;
        long downTime = SystemClock.uptimeMillis();
        int steps = 10;
        for (int i = 0; i <= steps; i++) {
            long eventTime = downTime + (durationMs * i / steps);
            float x = startX + (endX - startX) * i / steps;
            float y = startY + (endY - startY) * i / steps;
            int action = (i == 0) ? MotionEvent.ACTION_DOWN
                    : (i == steps) ? MotionEvent.ACTION_UP
                    : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            try {
                injectMethod.invoke(inputManager, event, 0);
            } catch (Exception e) { break; }
            event.recycle();
        }
    }
}
