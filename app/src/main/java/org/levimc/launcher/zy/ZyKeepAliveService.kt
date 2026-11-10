package org.levimc.launcher.zy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.levimc.launcher.R
import org.levimc.launcher.core.minecraft.MinecraftActivity

/**
 * Foreground service that runs while Minecraft is open. A foreground service raises the process
 * priority, so Android is far less likely to kill the game when the player switches to another app
 * for a moment; coming back then resumes instantly instead of loading Minecraft from scratch.
 */
class ZyKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "keep-alive unavailable: $t")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Game berjalan", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Menjaga Minecraft tetap hidup saat pindah app"
                    setShowBadge(false)
                })
        }
        val open = Intent(this, MinecraftActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_zy_boost)
            .setContentTitle("Minecraft berjalan")
            .setContentText("Zy Launcher menjaga game tetap hidup saat kamu pindah app.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "ZyKeepAlive"
        private const val CHANNEL_ID = "zy_keep_alive"
        private const val NOTIFICATION_ID = 0x5A59

        fun start(context: Context) {
            try {
                val intent = Intent(context, ZyKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "start failed: $t")
            }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, ZyKeepAliveService::class.java)) } catch (_: Throwable) {}
        }
    }
}
