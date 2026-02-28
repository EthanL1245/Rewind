package com.example.rewind.rewind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.rewind.R
import android.app.PendingIntent
import androidx.core.app.NotificationManagerCompat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class RewindService : Service() {

    private val sampleRate = 16000
    private val secondsInBuffer = 30
    private val bytesPerSample = 2 // PCM 16-bit
    private val ringSizeBytes = sampleRate * secondsInBuffer * bytesPerSample

    private var ring = ByteArray(ringSizeBytes)
    private var writePos = 0

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    companion object {
        const val ACTION_REWIND = "com.example.rewind.ACTION_REWIND"
    }

    private fun startRecordingIfNeeded() {
        if (isRecording.get()) return

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) return

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: SecurityException) {
            return
        }

        recorder = audioRecord
        audioRecord.startRecording()

        isRecording.set(true)
        recordThread = Thread {
            val temp = ByteArray(2048)
            while (isRecording.get()) {
                val n = audioRecord.read(temp, 0, temp.size)
                if (n > 0) {
                    writeToRing(temp, n)
                }
            }
        }.also { it.start() }
    }

    private fun stopRecordingIfNeeded() {
        isRecording.set(false)
        recordThread?.join(300)
        recordThread = null

        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        recorder = null
    }

    private fun writeToRing(src: ByteArray, count: Int) {
        var remaining = count
        var srcPos = 0

        while (remaining > 0) {
            val spaceToEnd = ring.size - writePos
            val toCopy = minOf(remaining, spaceToEnd)

            System.arraycopy(src, srcPos, ring, writePos, toCopy)

            writePos = (writePos + toCopy) % ring.size
            srcPos += toCopy
            remaining -= toCopy
        }
    }

    private fun snapshotRingChronological(): ByteArray {
        // Oldest audio starts at writePos, newest ends right before writePos
        val out = ByteArray(ring.size)
        val tail = ring.size - writePos
        System.arraycopy(ring, writePos, out, 0, tail)
        System.arraycopy(ring, 0, out, tail, writePos)
        return out
    }

    private fun saveSnapshotAsWav(pcm: ByteArray) {
        // Saves to the public Music/Rewind folder
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Rewind")
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "rewind_${System.currentTimeMillis()}.wav")

        FileOutputStream(file).use { fos ->
            fos.write(wavHeader(pcmDataLen = pcm.size, sampleRate = sampleRate, channels = 1, bitsPerSample = 16))
            fos.write(pcm)
            fos.flush()
        }
    }

    private fun wavHeader(pcmDataLen: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val totalDataLen = 36 + pcmDataLen

        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(totalDataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray())
        bb.putInt(pcmDataLen)
        return bb.array()
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "rewind",
                "Rewind Session",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start mic buffering for the session
        startRecordingIfNeeded()

        // Handle notification action
        if (intent?.action == ACTION_REWIND) {
            val pcm = snapshotRingChronological()
            saveSnapshotAsWav(pcm)
            return START_STICKY
        }

        val rewindIntent = Intent(this, RewindService::class.java).apply { action = ACTION_REWIND }
        val rewindPendingIntent = PendingIntent.getService(
            this,
            100,
            rewindIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "rewind")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("REWIND running")
            .setContentText("Listening buffer active")
            .setOngoing(true)
            .addAction(0, "REWIND", rewindPendingIntent)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecordingIfNeeded()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}