package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity

object PriceAlarmEngine {

    /**
     * Checks all ACTIVE alarms against the new price.
     * To be highly robust, if the price has crossed the threshold,
     * we trigger the alarm immediately and update its state to EXECUTED.
     */
    fun checkAndTriggerAlarms(context: Context, newPrice: Double) {
        val alarms = WidgetSettingsManager.getAlarms(context)
        val activeAlarms = alarms.filter { it.state == "ACTIVE" }
        if (activeAlarms.isEmpty()) return

        for (alarm in activeAlarms) {
            val triggered = if (alarm.isAbove) {
                newPrice >= alarm.targetPrice
            } else {
                newPrice <= alarm.targetPrice
            }

            if (triggered) {
                // Execute and trigger custom live notification
                WidgetSettingsManager.updateAlarmState(context, alarm.id, "EXECUTED", System.currentTimeMillis())
                triggerNotification(context, alarm, newPrice)
            }
        }
    }

    private fun triggerNotification(context: Context, alarm: PriceAlarm, currentPrice: Double) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = alarm.id.hashCode()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, com.example.services.AlarmDismissReceiver::class.java)
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 250,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val directionStr = if (alarm.isAbove) "UPWARD BREACH ▲" else "DOWNWARD BREACH ▼"
        val titleText = "🔔 XAU/USD PRICE TARGET EXCEEDED"
        val messageText = "Gold has crossed your target! Thresh: $${alarm.targetPrice} (${directionStr}). Actual level: $${currentPrice}"

        val channelId = if (alarm.alarmType == "SOUND_VIB") {
            createHighAlarmChannel(notificationManager)
            "XauUsdAlarmHighChannel"
        } else {
            createSimpleAlarmChannel(notificationManager)
            "XauUsdAlarmSimpleChannel"
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(titleText)
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_ALARM)

        if (alarm.alarmType == "SOUND_VIB") {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(pendingIntent, true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "DISMISS ALARM",
                    dismissPendingIntent
                )
            
            // Trigger robust physical looping alarm sounds and vibrations
            com.example.data.AlarmSoundManager.startAlarm(context)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
        }

        notificationManager.notify(notificationId, builder.build())
    }

    private fun createHighAlarmChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "XauUsdAlarmHighChannel",
                "Gold Price Alarms (High Intensity)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Triggers high intensity sound & vibration alarms on target crossing."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 1000)
                val alarmAudio = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(alarmAudio, audioAttributes)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun createSimpleAlarmChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "XauUsdAlarmSimpleChannel",
                "Gold Price Alarms (Simple)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Sends simple notifications with standard sounds."
            }
            manager.createNotificationChannel(channel)
        }
    }
}
