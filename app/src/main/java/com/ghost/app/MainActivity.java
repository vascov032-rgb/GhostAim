package com.ghost.app;
import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE_CAPTURE = 100;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this); 
        layout.setOrientation(LinearLayout.VERTICAL); 
        layout.setPadding(50, 50, 50, 50);

        Button btnOverlay = new Button(this); 
        btnOverlay.setText("1. Ativar Overlay");
        btnOverlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));

        Button btnA11y = new Button(this); 
        btnA11y.setText("2. Ativar Acessibilidade");
        btnA11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button btnStart = new Button(this); 
        btnStart.setText("3. Ativar Camera (MediaProjection)");
        btnStart.setOnClickListener(v -> {
            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE);
        });

        layout.addView(btnOverlay); layout.addView(btnA11y); layout.addView(btnStart); setContentView(layout);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_CAPTURE && resultCode == RESULT_OK) {
            Intent serviceIntent = new Intent(this, OverlayService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);
            startForegroundService(serviceIntent);
            finish();
        }
    }
}
