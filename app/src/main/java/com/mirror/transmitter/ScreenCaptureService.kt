package com.mirror.transmitter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket

class ScreenCaptureService : Service() {

    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var audioRecord: AudioRecord? = null

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("mirror", "MirrorSend", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val n: Notification = NotificationCompat.Builder(this, "mirror")
            .setContentTitle("MirrorSend")
            .setContentText("Transmitindo...")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .build()
        startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("RESULT_CODE", -1) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("RESULT_DATA") ?: return START_NOT_STICKY
        val ip   = intent.getStringExtra("RECEIVER_IP") ?: return START_NOT_STICKY
        val pm   = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(code, data)
        running = true
        startStreaming(ip)
        return START_NOT_STICKY
    }

    private fun startStreaming(ip: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = (metrics.widthPixels  / 2) and 0xFFFFFFFE.toInt()
        val h = (metrics.heightPixels / 2) and 0xFFFFFFFE.toInt()

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        vDisplay = projection?.createVirtualDisplay(
            "MirrorSend", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // ── Thread vídeo ─────────────────────────────────────────────────────
        Thread {
            var videoSock: Socket? = null
            try {
                Thread.sleep(500)
                videoSock = Socket(ip, 5555)
                videoSock.tcpNoDelay = true
                videoSock.setSendBufferSize(1024 * 1024)
                val out = DataOutputStream(videoSock.getOutputStream().buffered(512 * 1024))

                while (running) {
                    val img = imageReader?.acquireLatestImage()
                    if (img == null) { Thread.sleep(16); continue }
                    try {
                        val plane  = img.planes[0]
                        val rowPad = plane.rowStride - plane.pixelStride * w
                        val bmpW   = w + rowPad / plane.pixelStride
                        val full   = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
                        full.copyPixelsFromBuffer(plane.buffer)
                        val bmp    = if (bmpW > w) Bitmap.createBitmap(full, 0, 0, w, h) else full

                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 65, baos)
                        val bytes = baos.toByteArray()

                        out.writeByte(0x01)
                        out.writeInt(bytes.size)
                        out.write(bytes)
                        out.flush()

                        if (bmp !== full) bmp.recycle()
                        full.recycle()
                    } finally { img.close() }
                    Thread.sleep(40)
                }
            } catch (e: Exception) { e.printStackTrace() }
            finally { runCatching { videoSock?.close() } }
        }.also { it.isDaemon = true; it.name = "VideoSend" }.start()

        // ── Thread áudio (Android 10+) ────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Thread {
                var audioSock: Socket? = null
                try {
                    Thread.sleep(700)
                    audioSock = Socket(ip, 5557)
                    audioSock.tcpNoDelay = true
                    val out  = DataOutputStream(audioSock.getOutputStream())
                    val sr   = 44100
                    val cfg  = AudioFormat.CHANNEL_IN_STEREO
                    val enc  = AudioFormat.ENCODING_PCM_16BIT
                    val mBuf = AudioRecord.getMinBufferSize(sr, cfg, enc) * 4

                    val capCfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    audioRecord = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(capCfg)
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(enc).setSampleRate(sr).setChannelMask(cfg).build())
                        .setBufferSizeInBytes(mBuf)
                        .build()

                    audioRecord?.startRecording()
                    val buf = ByteArray(mBuf)
                    while (running) {
                        val n = audioRecord?.read(buf, 0, mBuf) ?: break
                        if (n > 0) {
                            out.writeByte(0x02)
                            out.writeInt(n)
                            out.write(buf, 0, n)
                            out.flush()
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
                finally {
                    runCatching { audioRecord?.stop(); audioRecord?.release() }
                    runCatching { audioSock?.close() }
                }
            }.also { it.isDaemon = true; it.name = "AudioSend" }.start()
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { vDisplay?.release() }
        runCatching { projection?.stop() }
        runCatching { imageReader?.close() }
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        super.onDestroy()
    }
}
