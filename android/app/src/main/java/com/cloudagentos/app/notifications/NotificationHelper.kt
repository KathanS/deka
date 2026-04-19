package com.cloudagentos.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cloudagentos.app.R

/**
 * Handles all notifications for CloudAgentOS automation tasks.
 *
 * Progress: "Searching milk on Blinkit... (step 3/8)"
 * Confirm: "Ready! Milk ₹30 + Bread ₹45 = ₹75 [Confirm] [Cancel]"
 * Done: "Order placed on Blinkit!"
 * Error: "Couldn't complete Blinkit order"
 */
object NotificationHelper {

    const val CHANNEL_TASK_PROGRESS = "task_progress"
    const val CHANNEL_TASK_ACTION = "task_action"
    const val NOTIFICATION_ID_PROGRESS = 1001
    const val NOTIFICATION_ID_CONFIRM = 1002
    const val NOTIFICATION_ID_RESULT = 1003

    const val ACTION_CONFIRM = "com.cloudagentos.ACTION_CONFIRM"
    const val ACTION_CANCEL = "com.cloudagentos.ACTION_CANCEL"
    const val EXTRA_TASK_ID = "task_id"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progressChannel = NotificationChannel(
            CHANNEL_TASK_PROGRESS,
            "Task Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while AI is working on your task"
            setShowBadge(false)
        }

        val actionChannel = NotificationChannel(
            CHANNEL_TASK_ACTION,
            "Task Actions",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Confirmations and results for completed tasks"
            setShowBadge(true)
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(actionChannel)
    }

    /**
     * Show ongoing progress notification.
     * "🔄 Searching milk on Blinkit... (step 3/8)"
     */
    fun showProgress(context: Context, appName: String, step: String, stepNum: Int, maxSteps: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_TASK_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Working on $appName...")
            .setContentText(step)
            .setProgress(maxSteps, stepNum, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_PROGRESS, notification)
        } catch (_: SecurityException) { }
    }

    /**
     * Show confirmation notification with Confirm/Cancel buttons.
     * "Ready! Milk ₹30 + Bread ₹45 = ₹75"
     */
    fun showConfirmation(context: Context, taskId: String, message: String, appName: String) {
        // Clear progress notification
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PROGRESS)

        val confirmIntent = Intent(ACTION_CONFIRM).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val cancelIntent = Intent(ACTION_CANCEL).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TASK_ID, taskId)
        }

        val confirmPending = PendingIntent.getBroadcast(
            context, 0, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelPending = PendingIntent.getBroadcast(
            context, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TASK_ACTION)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$appName — Confirm Order")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .addAction(android.R.drawable.ic_menu_send, "✅ Confirm", confirmPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "❌ Cancel", cancelPending)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_CONFIRM, notification)
        } catch (_: SecurityException) { }
    }

    /**
     * Show completion notification.
     * "✅ Order placed on Blinkit!"
     */
    fun showDone(context: Context, appName: String, message: String) {
        clearAll(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_TASK_ACTION)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✅ $appName")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_RESULT, notification)
        } catch (_: SecurityException) { }
    }

    /**
     * Show error notification.
     */
    fun showError(context: Context, appName: String, message: String) {
        clearAll(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_TASK_ACTION)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("❌ $appName")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_RESULT, notification)
        } catch (_: SecurityException) { }
    }

    fun clearAll(context: Context) {
        val mgr = NotificationManagerCompat.from(context)
        mgr.cancel(NOTIFICATION_ID_PROGRESS)
        mgr.cancel(NOTIFICATION_ID_CONFIRM)
    }
}
