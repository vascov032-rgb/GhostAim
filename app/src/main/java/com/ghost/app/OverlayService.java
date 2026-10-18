package com.ghost.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.view.View;
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
    
    private int fireX = 2020; 
    private int fireY = 922;  
    
    private int lastPixelSum = 0; // Para o Gatilho por Diferença

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
        
        // Texto flutuante no CANTO SUPERIOR ESQUERDO (não atrapalha o tiro)
        overlayText = new TextView(this); overlayText.setText("Ghost: OFF");
        overlayText.setTextColor(0xFFFF0000); overlayText.setBackgroundColor(0x99000000); overlayText.setPadding(20, 20, 20, 20);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = 50; params.y = 50;
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

        // --- GATILHO POR DIFERENÇA DE PIXEL ---
        // Lê uma caixa de 40x40 no local do botão e soma a cor
        int currentPixelSum = 0;
        for (int y = fireY - 20; y < fireY + 20; y += 4) {
            for (int x = fireX - 20; x < fireX + 20; x += 4) {
                if (x >= 0 && y >= 0 && x < bmpWidth && y < screenHeight) {
                    int p = bitmap.getPixel(x, y);
                    currentPixelSum += (Color.red(p) + Color.green(p) + Color.blue(p));
                }
            }
        }
        
        // Se a soma mudou muito em relação ao frame anterior, é porque o dedo encostou
        boolean isFiring = false;
        if (Math.abs(currentPixelSum - lastPixelSum) > 3000) {
            isFiring = true;
        }
        lastPixelSum = currentPixelSum; 
        // --------------------------------------

        if (isFiring) {
            updateOverlayText("TIRO! IA PROCURANDO...");
            
            // ZOOM NA IA: Pega apenas um quadrado de 800x800 no centro da tela
            int cropSize = 800;
            int cropX = (int)centerX - (cropSize / 2);
            int cropY = (int)centerY - (cropSize / 2);
            
            if (cropX < 0) cropX = 0;
            if (cropY < 0) cropY = 0;
            if (cropX + cropSize > bmpWidth) cropSize = bmpWidth - cropX;
            if (cropY + cropSize > screenHeight) cropSize = screenHeight - cropY;

            Bitmap cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropSize, cropSize);
            RectF head = yolo.detect(cropped);
            
            if (head != null) {
                updateOverlayText("CABECA ACHADA! MIRANDO...");
                // Soma as coordenadas do recorte de volta para a tela inteira
                float targetX = head.centerX() + cropX;
                float targetY = head.centerY() + cropY;

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
            cropped.recycle();
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
    
    @Override public void onDestroy() { 
        super.onDestroy(); 
        if (overlayText != null) windowManager.removeView(overlayText); 
        if (mediaProjection != null) mediaProjection.stop(); 
        if (backgroundThread != null) backgroundThread.quitSafely(); 
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
