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
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        const val EXTRA_RECEIVER_IP = "RECEIVER_IP"
        const val PORT_VIDEO = 5555
        const val PORT_AUDIO = 5557
        const val CHANNEL_ID = "ScreenMirrorChannel"
        const val TYPE_VIDEO: Byte = 0x01
        const val TYPE_AUDIO: Byte = 0x02
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private var socket: Socket? = null
    private var audioRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MirrorSend")
            .setContentText("Transmitindo tela e áudio...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
                val receiverIp = intent.getStringExtra(EXTRA_RECEIVER_IP) ?: return START_NOT_STICKY
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = manager.getMediaProjection(resultCode, resultData)
                startCapture(receiverIp)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(ip: String) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        val width = (metrics.widthPixels / 2) and 0xFFFE.inv().inv()   // múltiplo de 2
        val height = (metrics.heightPixels / 2) and 0xFFFE.inv().inv()
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MirrorCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        videoJob = CoroutineScope(Dispatchers.IO).launch {
            var sock: Socket? = null
            try {
                sock = Socket(ip, PORT_VIDEO)
                sock.tcpNoDelay = true
                sock.setSendBufferSize(512 * 1024)
                val out = DataOutputStream(sock.getOutputStream())
                socket = sock

                delay(300) // dar tempo ao VirtualDisplay inicializar

                while (isActive) {
                    val image = imageReader?.acquireLatestImage()
                    if (image == null) {
                        delay(16)
                        continue
                    }
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmapW = width + rowPadding / pixelStride
                        val full = Bitmap.createBitmap(bitmapW, height, Bitmap.Config.ARGB_8888)
                        full.copyPixelsFromBuffer(buffer)

                        val bmp = if (bitmapW > width) Bitmap.createBitmap(full, 0, 0, width, height) else full

                        val baos = ByteArrayOutputStream(width * height / 4)
                        bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                        val bytes = baos.toByteArray()

                        // Protocolo: [type:1][size:4][data:N]
                        out.writeByte(TYPE_VIDEO.toInt())
                        out.writeInt(bytes.size)
                        out.write(bytes)
                        out.flush()

                        if (bmp !== full) bmp.recycle()
                        full.recycle()
                    } finally {
                        image.close()
                    }
                    delay(33)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                sock?.close()
            }
        }

        // Captura de áudio (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioJob = CoroutineScope(Dispatchers.IO).launch {
                var audioSock: Socket? = null
                try {
                    delay(500)
                    audioSock = Socket(ip, PORT_AUDIO)
                    audioSock.tcpNoDelay = true
                    val out = DataOutputStream(audioSock.getOutputStream())

                    val sampleRate = 44100
                    val channelConfig = AudioFormat.CHANNEL_IN_STEREO
                    val encoding = AudioFormat.ENCODING_PCM_16BIT
                    val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
                    val bufSize = minBuf * 4

                    val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    audioRecord = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(config)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(encoding)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                        )
                        .setBufferSizeInBytes(bufSize)
                        .build()

                    audioRecord?.startRecording()

                    val buf = ByteArray(bufSize)
                    while (isActive) {
                        val read = audioRecord?.read(buf, 0, bufSize) ?: break
                        if (read > 0) {
                            out.writeByte(TYPE_AUDIO.toInt())
                            out.writeInt(read)
                            out.write(buf, 0, read)
                            out.flush()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioSock?.close()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Screen Mirror", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        videoJob?.cancel()
        audioJob?.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        socket?.close()
        super.onDestroy()
    }
}
