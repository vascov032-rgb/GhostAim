package com.ghost.app;
import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE_CAPTURE = 100;
    private EditText edtX, edtY;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this); 
        layout.setOrientation(LinearLayout.VERTICAL); 
        layout.setPadding(50, 50, 50, 50);

        TextView txtInfo = new TextView(this);
        txtInfo.setText("Digite as coordenadas do Botao de Tiro:");
        layout.addView(txtInfo);

        edtX = new EditText(this); edtX.setHint("Coordenada X (ex: 2020)"); layout.addView(edtX);
        edtY = new EditText(this); edtY.setHint("Coordenada Y (ex: 922)"); layout.addView(edtY);

        Button btnSalvar = new Button(this); btnSalvar.setText("Salvar Coordenadas");
        btnSalvar.setOnClickListener(v -> {
            getSharedPreferences("GhostPrefs", MODE_PRIVATE).edit()
                .putInt("fireX", Integer.parseInt(edtX.getText().toString()))
                .putInt("fireY", Integer.parseInt(edtY.getText().toString()))
                .apply();
            Toast.makeText(this, "Salvo!", Toast.LENGTH_SHORT).show();
        });
        layout.addView(btnSalvar);

        Button btnOverlay = new Button(this); 
        btnOverlay.setText("1. Ativar Overlay");
        btnOverlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        layout.addView(btnOverlay);

        Button btnA11y = new Button(this); 
        btnA11y.setText("2. Ativar Acessibilidade");
        btnA11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        layout.addView(btnA11y);

        Button btnStart = new Button(this); 
        btnStart.setText("3. Ativar Camera e Iniciar");
        btnStart.setOnClickListener(v -> {
            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE);
        });
        layout.addView(btnStart); 

        setContentView(layout);
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
