package com.ghost.app;

import android.os.ParcelFileDescriptor;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import rikka.shizuku.Shizuku;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;

public class ShizukuInputInjector {
    public static boolean init() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static void swipe(float startX, float startY, float endX, float endY, long durationMs) {
        String cmd = "input swipe " + (int)startX + " " + (int)startY + " " + (int)endX + " " + (int)endY + " " + durationMs;
        
        try {
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            IRemoteProcess process = service.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            
            ParcelFileDescriptor pfd = process.getInputStream();
            if (pfd != null) {
                FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                while ((reader.readLine()) != null) {} // Consome saída
                reader.close();
            }
            
            process.waitFor();
        } catch (Exception e) {
            // Silencioso
        }
    }
}
