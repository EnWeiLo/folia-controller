package tw.folia.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/**
 * 顯示器模式: turns an old phone into a lyrics display. While it is on this service waits (with a small
 * notification) and opens 流詞 the moment the charger is plugged in. The app itself keeps the screen on
 * only while charging and dims it at night. Also started again after a reboot (see [BootReceiver]).
 */
class DisplayModeService : Service() {
    companion object {
        private const val CHANNEL = "display_mode"
        private const val NOTIFICATION_ID = 8
        const val EXTRA_LAUNCH = "display_launch"

        @Volatile var running = false
            private set

        fun canDraw(context: Context) = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val intent = Intent(context, DisplayModeService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                // Not allowed to start from the background right now; the next app start tries again.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DisplayModeService::class.java))
        }

        fun isCharging(context: Context): Boolean {
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }

        /** Brings 流詞 to the front, waking the screen. Apps with the overlay permission may do this from the background. */
        fun launch(context: Context) {
            if (!canDraw(context)) return
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_LAUNCH, true)
            try { context.startActivity(open) } catch (e: Exception) { }
        }
    }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) launch(context)
        }
    }
    private var registered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "顯示器模式", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "顯示器模式待命時的常駐通知"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_fc_lyrics)
            .setContentTitle("顯示器模式待命中")
            .setContentText("插上充電線就會自動打開流詞")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!registered) {
            registerReceiver(power, IntentFilter(Intent.ACTION_POWER_CONNECTED))
            registered = true
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (registered) {
            runCatching { unregisterReceiver(power) }
            registered = false
        }
        super.onDestroy()
    }
}

/** After a reboot (or an app update) bring 顯示器模式 back: wait for the charger, or open right away if already charging. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("folia_controller", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("display_mode", false) || !DisplayModeService.canDraw(context)) return
        DisplayModeService.start(context)
        if (DisplayModeService.isCharging(context)) DisplayModeService.launch(context)
    }
}
