package com.example.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AlarmSoundManager

class AlarmDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("AlarmDismissReceiver", "Dismiss broadcast received!")
        AlarmSoundManager.stopAlarm(context)
        
        // Cancel all notifications upon explicit action
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancelAll()
    }
}
