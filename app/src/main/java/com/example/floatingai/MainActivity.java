package com.example.floatingai;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText editApiKey = findViewById(R.id.editApiKey);
        EditText editApiUrl = findViewById(R.id.editApiUrl);
        EditText editModel = findViewById(R.id.editModel);
        Button btnStart = findViewById(R.id.btnStartService);

        SharedPreferences prefs = getSharedPreferences("AiPrefs", MODE_PRIVATE);
        editApiKey.setText(prefs.getString("api_key", ""));
        editApiUrl.setText(prefs.getString("api_url", "https://api.openai.com/v1/chat/completions"));
        editModel.setText(prefs.getString("model", "gpt-3.5-turbo"));

        btnStart.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_CODE);
                Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.edit()
                .putString("api_key", editApiKey.getText().toString())
                .putString("api_url", editApiUrl.getText().toString())
                .putString("model", editModel.getText().toString())
                .apply();

            startService(new Intent(MainActivity.this, FloatingService.class));
            finish(); 
        });
    }
}
