package com.mirror.transmitter

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var mgr: MediaProjectionManager
    private val REQ = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val etIp     = findViewById<EditText>(R.id.etIp)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop  = findViewById<Button>(R.id.btnStop)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnStart.setOnClickListener {
            val ip = etIp.text.toString().trim()
            if (ip.isEmpty()) { tvStatus.text = "⚠ Digite o IP da TV Box!"; return@setOnClickListener }
            tvStatus.text = "⏳ Aguardando permissão..."
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            tvStatus.text = "⏹ Parado."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == Activity.RESULT_OK && data != null) {
            val ip = findViewById<EditText>(R.id.etIp).text.toString().trim()
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("RESULT_DATA", data)
                putExtra("RECEIVER_IP", ip)
            }
            startForegroundService(intent)
            findViewById<TextView>(R.id.tvStatus).text = "📡 Transmitindo para $ip..."
        }
    }
}
