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
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.rewind.BuildConfig

class RewindService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val http = OkHttpClient()

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
        arr.put("Moment")
        obj.put("tags", arr)

        // AI fields (filled in later)
        obj.put("title", "Summarizing…")
        obj.put("summary", "")
        obj.put("transcript", "")
        obj.put("aiStatus", "pending")

        jsonFile.writeText(obj.toString())

        // Kick off Gemini summarization immediately (best UX)
        serviceScope.launch {
            try {
                val (title, summary, transcript) = geminiGenerateTitleSummaryTranscript(wavFile)
                updateJsonFields(jsonFile) { obj ->
                    obj.put("title", title)
                    obj.put("summary", summary)
                    obj.put("transcript", transcript)
                    obj.put("aiStatus", "done")
                }
            } catch (e: Exception) {
                updateJsonFields(jsonFile) { obj ->
                    obj.put("aiStatus", "error")
                    obj.put("aiError", e.message ?: "unknown")
                }
            }
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
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun updateJsonFields(jsonFile: File, updater: (org.json.JSONObject) -> Unit) {
        val obj = runCatching { org.json.JSONObject(jsonFile.readText()) }.getOrNull() ?: org.json.JSONObject()
        updater(obj)
        jsonFile.writeText(obj.toString())
    }

    private fun geminiGenerateTitleSummaryTranscript(wavFile: File): Triple<String, String, String> {
        val apiKey = BuildConfig.GEMINI_API_KEY
        require(apiKey.isNotBlank()) { "Missing GEMINI_API_KEY" }

        // Use a "latest" Flash model for speed (names change; check docs if needed) :contentReference[oaicite:4]{index=4}
        val model = "gemini-flash-latest"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val wavBytes = wavFile.readBytes()
        val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        // Ask Gemini to transcribe + title + summarize and return JSON only.
        val prompt = """
Return ONLY valid JSON with keys: title, summary, transcript.
- title: short (max 8 words), helpful.
- summary: 2-4 bullet points (use "-" lines) OR 2 short sentences.
- transcript: the transcription of the audio (plain text).
""".trimIndent()

        val bodyJson = org.json.JSONObject().apply {
            put("generationConfig", org.json.JSONObject().apply {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
            })
            put("contents", org.json.JSONArray().put(
                org.json.JSONObject().apply {
                    put("role", "user")
                    put("parts", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply { put("text", prompt) })
                        put(org.json.JSONObject().apply {
                            put("inlineData", org.json.JSONObject().apply {
                                put("mimeType", "audio/wav")
                                put("data", b64)
                            })
                        })
                    })
                }
            ))
        }.toString()

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Gemini error ${resp.code}: $text")
            }

            // Gemini returns candidates[0].content.parts[0].text (which should be JSON string)
            val root = org.json.JSONObject(text)
            val jsonText = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .optString("text", "{}")

            val out = org.json.JSONObject(jsonText)
            val title = out.optString("title", "Untitled")
            val summary = out.optString("summary", "")
            val transcript = out.optString("transcript", "")
            return Triple(title, summary, transcript)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}