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

    private lateinit var projectionManager: MediaProjectionManager
    private val REQUEST_CODE = 100
    private var receiverIp = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val ipInput = findViewById<EditText>(R.id.etIp)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val statusText = findViewById<TextView>(R.id.tvStatus)

        btnStart.setOnClickListener {
            receiverIp = ipInput.text.toString().trim()
            if (receiverIp.isEmpty()) {
                statusText.text = "Digite o IP do receptor!"
                return@setOnClickListener
            }
            statusText.text = "Solicitando permissão..."
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
        }

        btnStop.setOnClickListener {
            val stopIntent = Intent(this, ScreenCaptureService::class.java)
            stopIntent.action = ScreenCaptureService.ACTION_STOP
            startService(stopIntent)
            statusText.text = "Transmissão parada."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_RECEIVER_IP, receiverIp)
            }
            startForegroundService(serviceIntent)
            findViewById<TextView>(R.id.tvStatus).text = "Transmitindo para $receiverIp..."
        }
    }
}
