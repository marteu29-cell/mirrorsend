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

    companion object {
        const val ACTION_START        = "ACTION_START"
        const val ACTION_STOP         = "ACTION_STOP"
        const val EXTRA_RESULT_CODE   = "RESULT_CODE"
        const val EXTRA_RESULT_DATA   = "RESULT_DATA"
        const val EXTRA_RECEIVER_IP   = "RECEIVER_IP"
        const val PORT_VIDEO          = 5555
        const val PORT_AUDIO          = 5557
        const val CHANNEL_ID          = "MirrorSendChannel"
        const val TYPE_VIDEO          = 0x01.toByte()
        const val TYPE_AUDIO          = 0x02.toByte()
    }

    @Volatile private var running = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var audioRecord: AudioRecord? = null
    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MirrorSend")
            .setContentText("Transmitindo tela...")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
            val receiverIp = intent.getStringExtra(EXTRA_RECEIVER_IP) ?: return START_NOT_STICKY

            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
            running = true
            startCapture(receiverIp)
        } else if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(ip: String) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        // Metade da resolução real
        val width   = (metrics.widthPixels  / 2) and 0xFFFFFFFE.toInt()
        val height  = (metrics.heightPixels / 2) and 0xFFFFFFFE.toInt()
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MirrorCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // Thread de vídeo
        Thread {
            try {
                videoSocket = Socket(ip, PORT_VIDEO)
                videoSocket!!.tcpNoDelay = true
                videoSocket!!.setSendBufferSize(512 * 1024)
                val out = DataOutputStream(videoSocket!!.getOutputStream())

                Thread.sleep(400) // aguardar VirtualDisplay inicializar

                while (running) {
                    val image = imageReader?.acquireLatestImage()
                    if (image == null) {
                        Thread.sleep(16)
                        continue
                    }
                    try {
                        val plane      = image.planes[0]
                        val buf        = plane.buffer
                        val pixStride  = plane.pixelStride
                        val rowStride  = plane.rowStride
                        val rowPad     = rowStride - pixStride * width
                        val bmpW       = width + rowPad / pixStride

                        val full = Bitmap.createBitmap(bmpW, height, Bitmap.Config.ARGB_8888)
                        full.copyPixelsFromBuffer(buf)

                        val bmp = if (bmpW > width) Bitmap.createBitmap(full, 0, 0, width, height) else full

                        val baos = ByteArrayOutputStream(width * height / 4)
                        bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                        val bytes = baos.toByteArray()

                        out.writeByte(TYPE_VIDEO.toInt())
                        out.writeInt(bytes.size)
                        out.write(bytes)
                        out.flush()

                        if (bmp !== full) bmp.recycle()
                        full.recycle()
                    } finally {
                        image.close()
                    }
                    Thread.sleep(33) // ~30 fps
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { videoSocket?.close() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; name = "Video-Sender"; start() }

        // Thread de áudio (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Thread {
                try {
                    Thread.sleep(600)
                    audioSocket = Socket(ip, PORT_AUDIO)
                    audioSocket!!.tcpNoDelay = true
                    val out = DataOutputStream(audioSocket!!.getOutputStream())

                    val sampleRate    = 44100
                    val channelConfig = AudioFormat.CHANNEL_IN_STEREO
                    val encoding      = AudioFormat.ENCODING_PCM_16BIT
                    val minBuf        = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
                    val bufSize       = minBuf * 4

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

                    while (running) {
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
                    try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
                    try { audioSocket?.close() } catch (_: Exception) {}
                }
            }.apply { isDaemon = true; name = "Audio-Sender"; start() }
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "MirrorSend", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        running = false
        try { virtualDisplay?.release() }   catch (_: Exception) {}
        try { mediaProjection?.stop() }     catch (_: Exception) {}
        try { imageReader?.close() }        catch (_: Exception) {}
        try { audioRecord?.stop() }         catch (_: Exception) {}
        try { audioRecord?.release() }      catch (_: Exception) {}
        try { videoSocket?.close() }        catch (_: Exception) {}
        try { audioSocket?.close() }        catch (_: Exception) {}
        super.onDestroy()
    }
}
