package com.ghost.app;

import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import rikka.shizuku.Shizuku;

public class ShizukuInputInjector {
    public static boolean init() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static void swipe(float startX, float startY, float endX, float endY, long durationMs) {
        // Executa o comando 'input swipe' com permissão de shell (UID 2000) via Shizuku
        String cmd = "input swipe " + (int)startX + " " + (int)startY + " " + (int)endX + " " + (int)endY + " " + durationMs;
        
        try {
            Process process = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                // Apenas consome a saída para evitar bloqueio
            }
            process.waitFor();
        } catch (Exception e) {
            // Silencioso para não travar o loop de captura
        }
    }
}
