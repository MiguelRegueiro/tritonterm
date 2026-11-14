package com.tritonterm.android.terminal

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
import com.tritonterm.android.MainActivity
import com.tritonterm.android.R

/** Keeps a user-started terminal or first-run provisioning process important while backgrounded. */
internal class TerminalForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Terminal session", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val setup = intent?.action == ACTION_SETUP
        if (intent?.action == ACTION_EXIT) {
            // Termux-style Exit closes the active PTY as well as the persistent notification.
            TerminalSessionHost.controller(applicationContext).resetSession()
            TerminalSessionHost.markSessionInactive(applicationContext)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            TerminalDiagnostics.record(applicationContext, "foreground_service", "exit_action")
            return START_NOT_STICKY
        }
        val notification = notification(setup)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        TerminalDiagnostics.record(applicationContext, "foreground_service", "started", mapOf("work" to if (setup) "setup" else "terminal"))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        TerminalDiagnostics.record(applicationContext, "foreground_service", "task_removed", mapOf("service_continues" to true))
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        TerminalDiagnostics.record(applicationContext, "foreground_service", "destroyed")
        super.onDestroy()
    }

    private fun notification(setup: Boolean): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_triton)
            .setContentTitle("TritonTerm")
            .setContentText(if (setup) "Preparing Arch Linux ARM" else "Terminal session running")
            .setContentIntent(launch)
            .setShowWhen(false)
            .apply {
                if (!setup) {
                    val exit = PendingIntent.getService(
                        this@TerminalForegroundService,
                        1,
                        Intent(this@TerminalForegroundService, TerminalForegroundService::class.java)
                            .setAction(ACTION_EXIT),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exit)
                }
            }
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tritonterm_terminal"
        private const val NOTIFICATION_ID = 1701
        private const val ACTION_SETUP = "com.tritonterm.android.action.SETUP"
        private const val ACTION_SESSION = "com.tritonterm.android.action.SESSION"
        private const val ACTION_EXIT = "com.tritonterm.android.action.EXIT"

        fun startSession(context: Context) {
            TerminalSessionHost.markSessionActive(context.applicationContext)
            start(context, ACTION_SESSION)
        }
        fun startSetup(context: Context) = start(context, ACTION_SETUP)
        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context, TerminalForegroundService::class.java))
        }

        private fun start(context: Context, action: String) {
            val app = context.applicationContext
            val intent = Intent(app, TerminalForegroundService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        }
    }
}
