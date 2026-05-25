package com.mirror.transmitter

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private val REQUEST_CODE = 100
    private var receiverIp: String? = null
    private var discoverJob: Job? = null

    companion object {
        const val UDP_PORT = 5556
        const val BROADCAST_PREFIX = "MIRRORRECEIVE_HERE:"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop  = findViewById<Button>(R.id.btnStop)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnStart.isEnabled = false
        tvStatus.text = "🔍 Procurando TV Box na rede..."
        startDiscovery(tvStatus, btnStart)

        btnStart.setOnClickListener {
            val ip = receiverIp ?: return@setOnClickListener
            discoverJob?.cancel()
            tvStatus.text = "⏳ Solicitando permissão de captura..."
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
        }

        btnStop.setOnClickListener {
            val si = Intent(this, ScreenCaptureService::class.java)
            si.action = ScreenCaptureService.ACTION_STOP
            startService(si)
            tvStatus.text = "⏹ Parado. Procurando TV Box..."
            btnStart.isEnabled = false
            receiverIp = null
            startDiscovery(tvStatus, btnStart)
        }
    }

    private fun startDiscovery(tvStatus: TextView, btnStart: Button) {
        discoverJob?.cancel()
        discoverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket(UDP_PORT)
                socket.soTimeout = 5000
                val buf = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length).trim()
                        if (msg.startsWith(BROADCAST_PREFIX)) {
                            val ip = msg.removePrefix(BROADCAST_PREFIX).trim()
                            receiverIp = ip
                            withContext(Dispatchers.Main) {
                                tvStatus.text = "✅ TV Box encontrada: $ip\nToque em Iniciar para transmitir"
                                btnStart.isEnabled = true
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        withContext(Dispatchers.Main) {
                            if (receiverIp == null)
                                tvStatus.text = "🔍 Procurando TV Box...\n(abra o MirrorReceive na TV)"
                        }
                    }
                }
                socket.close()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Erro: ${e.message}"
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val ip = receiverIp ?: return
            val si = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_RECEIVER_IP, ip)
            }
            startForegroundService(si)
            findViewById<TextView>(R.id.tvStatus).text = "📡 Transmitindo para $ip..."
        }
    }

    override fun onDestroy() {
        discoverJob?.cancel()
        super.onDestroy()
    }
}
