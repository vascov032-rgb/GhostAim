package com.ghost.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;
import moe.shizuku.server.IShizukuService;

public class ShizukuInputInjector {
    public static boolean init() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static void swipe(float startX, float startY, float endX, float endY, long durationMs) {
        // Executa o comando 'input swipe' com permissão de shell (UID 2000) via Shizuku
        String cmd = "input swipe " + (int)startX + " " + (int)startY + " " + (int)endX + " " + (int)endY + " " + durationMs;
        
        try {
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            ShizukuRemoteProcess process = service.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while ((reader.readLine()) != null) {} // Consome saída
            process.waitFor();
        } catch (Exception e) {
            // Silencioso para não travar o loop
        }
    }
}
