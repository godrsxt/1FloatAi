package com.floatingai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val btnSaveAndStart = findViewById<Button>(R.id.btnSaveAndStart)

        val prefs = getSharedPreferences("AiPrefs", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("API_KEY", ""))

        btnSaveAndStart.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("API_KEY", key).apply()

            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, 1234)
            } else {
                startService(Intent(this, FloatingService::class.java))
                finish() // Close the app UI, leave floating window
            }
        }
    }
}
