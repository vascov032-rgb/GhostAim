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

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;
    private YoloDetector yolo;
    
    private int fireX = 2020; // Valor pego na sua print
    private int fireY = 922;  // Valor pego na sua print

    private long lastAimTime = 0;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    @Override public void onCreate() {
        super.onCreate();
        startForegroundService();
        yolo = new YoloDetector(this);
        
        fireX = getSharedPreferences("GhostPrefs", MODE_PRIVATE).getInt("fireX", 2020);
        fireY = getSharedPreferences("GhostPrefs", MODE_PRIVATE).getInt("fireY", 922);

        backgroundThread = new HandlerThread("GhostThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayText = new TextView(this); overlayText.setText("Ghost: OFF");
        overlayText.setTextColor(0xFFFF0000); overlayText.setBackgroundColor(0x99000000); overlayText.setPadding(20, 20, 20, 20);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = 50; params.y = 200;
        windowManager.addView(overlayText, params);
        
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
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAimTime < 80) return; 

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

        // --- ÁREA DE TOLERÂNCIA (Caixa de 300x300 ao redor do centro) ---
        int tolerance = 150; // 150 pixels para cada lado
        int startX = Math.max(0, fireX - tolerance);
        int endX = Math.min(screenWidth, fireX + tolerance);
        int startY = Math.max(0, fireY - tolerance);
        int endY = Math.min(screenHeight, fireY + tolerance);
        
        int brightPixels = 0;
        int totalPixels = 0;
        
        for (int y = startY; y < endY; y += 10) {
            for (int x = startX; x < endX; x += 10) {
                int p = bitmap.getPixel(x, y);
                int brightness = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3;
                // A cor do dedo/botão apertado clareia a área
                if (brightness > 110) { 
                    brightPixels++;
                }
                totalPixels++;
            }
        }
        
        // Se 10% dessa área ficar clara, é porque o dedo encostou
        boolean isFiring = (brightPixels > (totalPixels * 0.10)); 

        if (isFiring) {
            updateOverlayText("TIRO! IA PROCURANDO...");
            RectF head = yolo.detect(bitmap);
            
            if (head != null) {
                updateOverlayText("CABECA ACHADA! MIRANDO...");
                float targetX = head.centerX(); float targetY = head.centerY();

                if (Math.abs(targetX - centerX) > 15 || Math.abs(targetY - centerY) > 15) {
                    float deltaX = (targetX - centerX) * 0.8f; 
                    float deltaY = (targetY - centerY) * 0.8f;
                    float swipeEndX = centerX - deltaX;
                    float swipeEndY = centerY - deltaY;
                    if (GhostAccessibilityService.instance != null) {
                        GhostAccessibilityService.instance.swipeAim(centerX, centerY, swipeEndX, swipeEndY);
                    }
                }
            } else {
                updateOverlayText("TIRO, MAS IA NAO ACHOU");
            }
            lastAimTime = currentTime;
        } else {
            updateOverlayText("Ghost: ON (Aguardando Tiro)");
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
