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

class RewindService : Service() {

    companion object {
        const val ACTION_REWIND = "com.example.rewind.ACTION_REWIND"
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
        if (intent?.action == ACTION_REWIND) {
            // TODO: save last 30s buffer
            return START_STICKY
        }
        val rewindIntent = Intent(this, RewindService::class.java).apply {
            action = ACTION_REWIND
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

    override fun onBind(intent: Intent?): IBinder? = null
}