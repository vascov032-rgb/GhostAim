package com.ghost.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private TextView overlayText;
    public static boolean isCheatActive = false;
    private boolean shizukuReady = false;

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;
    private YoloDetector yolo;
    private boolean targetLocked = false;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    @Override public void onCreate() {
        super.onCreate();
        startForegroundService();
        yolo = new YoloDetector(this);
        
        backgroundThread = new HandlerThread("GhostThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayText = new TextView(this); overlayText.setText("Ghost: OFF");
        overlayText.setTextColor(0xFFFF0000); overlayText.setBackgroundColor(0x99000000); overlayText.setPadding(20, 20, 20, 20);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = 50; params.y = 50;
        windowManager.addView(overlayText, params);

        // Verifica o Shizuku a cada 2 segundos
        new Thread(() -> {
            while (true) {
                if (!shizukuReady) {
                    shizukuReady = ShizukuInputInjector.init();
                }
                try { Thread.sleep(2000); } catch (Exception e) {}
            }
        }).start();

        startProxyServer();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("resultCode")) {
            startMediaProjection(intent.getIntExtra("resultCode", -1), (Intent) intent.getParcelableExtra("data"));
        }
        return START_STICKY;
    }

    private void startMediaProjection(int resultCode, Intent data) {
        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        mediaProjection.createVirtualDisplay("GhostCapture", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                if (isCheatActive) { processFrame(image); }
                image.close();
            }
        }, backgroundHandler);
    }

    private void processFrame(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;
        int bmpWidth = screenWidth + rowPadding / pixelStride;
        Bitmap bitmap = Bitmap.createBitmap(bmpWidth, screenHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);

        float centerX = screenWidth / 2f;
        float centerY = screenHeight / 2f;
        int centerPixel = bitmap.getPixel((int)centerX, (int)centerY);
        int r = Color.red(centerPixel), g = Color.green(centerPixel), b = Color.blue(centerPixel);
        boolean isAiming = (r > 100 && g < 90 && b < 90);

        if (isAiming) {
            if (!targetLocked) {
                int cropSize = 800;
                int cropX = Math.max(0, (int)centerX - (cropSize / 2));
                int cropY = Math.max(0, (int)centerY - (cropSize / 2));
                if (cropX + cropSize > bmpWidth) cropSize = bmpWidth - cropX;
                if (cropY + cropSize > screenHeight) cropSize = screenHeight - cropY;
                Bitmap cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropSize, cropSize);
                
                try {
                    RectF head = yolo.detect(cropped);
                    String confStr = String.format("%.0f%%", YoloDetector.lastMaxConf * 100);
                    
                    if (head != null) {
                        updateOverlayText("SNAP SHIZUKU (" + confStr + ")");
                        float targetX = head.centerX() + cropX;
                        float targetY = head.centerY() + cropY;
                        
                        float deltaX = targetX - centerX;
                        float deltaY = targetY - centerY;
                        
                        // Para mover a câmera para a direita, o dedo deve arrastar para a esquerda.
                        // Se o alvo está na direita (deltaX > 0), o swipe deve ser centerX - deltaX.
                        float swipeEndX = centerX - deltaX;
                        float swipeEndY = centerY - deltaY;

                        if (shizukuReady) {
                            ShizukuInputInjector.swipe(centerX, centerY, swipeEndX, swipeEndY, 200);
                        }
                        targetLocked = true;
                    } else {
                        updateOverlayText("MIRA VERMELHA. IA VIU: " + confStr);
                    }
                } catch (Exception e) {}
                cropped.recycle();
            } else {
                updateOverlayText("ALVO TRAVADO (Shizuku)");
            }
        } else {
            targetLocked = false;
            if (shizukuReady) {
                updateOverlayText("Ghost: ON (Shizuku OK)");
            } else {
                updateOverlayText("Ghost: ON (Aguardando Shizuku)");
            }
        }
        bitmap.recycle();
    }

    private void startProxyServer() {
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(5555);
                while (true) {
                    Socket socket = server.accept();
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String cmd = in.readLine();
                    if (cmd != null && cmd.startsWith("ACTIVATE")) {
                        isCheatActive = true; updateOverlayText("Ghost: ON");
                    } else if (cmd != null && cmd.startsWith("STOP")) {
                        isCheatActive = false; updateOverlayText("Ghost: OFF");
                    }
                    socket.close();
                }
            } catch (Exception e) {}
        }).start();
    }

    private void startForegroundService() {
        String channelId = "ghost_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "GhostAim", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, channelId).setContentTitle("GhostAim Ativo").setSmallIcon(android.R.drawable.ic_menu_camera).build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else { startForeground(1, notification); }
        }
    }
    private void updateOverlayText(String text) { if (overlayText != null) overlayText.post(() -> { overlayText.setText(text); }); }
    @Override public void onDestroy() { super.onDestroy(); if (overlayText != null) windowManager.removeView(overlayText); if (mediaProjection != null) mediaProjection.stop(); if (backgroundThread != null) backgroundThread.quitSafely(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
