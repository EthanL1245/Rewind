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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class RewindService : Service() {

    private val sampleRate = 16000
    private val maxSecondsInBuffer = 120   // ring holds up to 2 minutes
    private val bytesPerSample = 2         // PCM 16-bit
    private val ringSizeBytes = sampleRate * maxSecondsInBuffer * bytesPerSample

    private var ring = ByteArray(ringSizeBytes)
    private var writePos = 0

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    private var currentRewindSeconds = 30
    private var stopPosted = false

    companion object {
        const val ACTION_REWIND = "com.example.rewind.ACTION_REWIND"
        const val EXTRA_REWIND_SECONDS = "extra_rewind_seconds"
        const val EXTRA_SESSION_SECONDS = "extra_session_seconds"
        const val ACTION_STOP = "com.example.rewind.ACTION_STOP"
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

    private fun snapshotLastSeconds(seconds: Int): ByteArray {
        val clamped = seconds.coerceIn(1, maxSecondsInBuffer)
        val bytesToCopy = sampleRate * clamped * bytesPerSample

        val out = ByteArray(bytesToCopy)

        // newest audio ends right before writePos, so start = writePos - bytesToCopy (wrapped)
        var start = writePos - bytesToCopy
        while (start < 0) start += ring.size

        val tail = ring.size - start
        if (bytesToCopy <= tail) {
            System.arraycopy(ring, start, out, 0, bytesToCopy)
        } else {
            System.arraycopy(ring, start, out, 0, tail)
            System.arraycopy(ring, 0, out, tail, bytesToCopy - tail)
        }

        return out
    }

    private fun saveSnapshotAsWav(pcm: ByteArray, secondsUsed: Int) {
        val dir = getExternalFilesDir(null) ?: filesDir
        val base = "rewind_${System.currentTimeMillis()}"
        val wavFile = File(dir, "$base.wav")
        val jsonFile = File(dir, "$base.json")

        FileOutputStream(wavFile).use { fos ->
            fos.write(wavHeader(pcm.size, sampleRate, 1, 16))
            fos.write(pcm)
            fos.flush()
        }

        // store capsule metadata
        val obj = org.json.JSONObject()
        obj.put("seconds", secondsUsed)
        val arr = org.json.JSONArray()
        arr.put("Moment") // default tag
        obj.put("tags", arr)
        jsonFile.writeText(obj.toString())
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
        if (intent?.action == ACTION_STOP) {
            stopRecordingIfNeeded()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startRecordingIfNeeded()

        val rewindSeconds = intent?.getIntExtra(EXTRA_REWIND_SECONDS, 30) ?: 30
        val sessionSeconds = intent?.getIntExtra(EXTRA_SESSION_SECONDS, 60 * 60) ?: (60 * 60)

        // auto-stop timer (simple MVP)
        if (!stopPosted) {
            stopPosted = true
            android.os.Handler(mainLooper).postDelayed({
                stopRecordingIfNeeded()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                stopPosted = false
            }, sessionSeconds * 1000L)
        }

        // Handle notification action
        if (intent?.action == ACTION_REWIND) {
            val pcm = snapshotLastSeconds(rewindSeconds)
            saveSnapshotAsWav(pcm, rewindSeconds)
            return START_STICKY
        }

        val rewindIntent = Intent(this, RewindService::class.java).apply {
            action = ACTION_REWIND
            putExtra(EXTRA_REWIND_SECONDS, rewindSeconds)
        }
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