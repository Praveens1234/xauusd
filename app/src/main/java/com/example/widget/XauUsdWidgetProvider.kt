package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R as AppR
import com.example.services.XauUsdWidgetService

class XauUsdWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d("XauUsdWidgetProvider", "onUpdate called for ${appWidgetIds.size} widgets")
        
        // Always try to start the Foreground Service to keep it alive or refresh
        startLiveService(context)
        
        // Also perform an initial basic draw update
        for (appWidgetId in appWidgetIds) {
            updateAppWidgetDefault(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        Log.d("XauUsdWidgetProvider", "Widget onEnabled - starting background tracking service")
        startLiveService(context)
    }

    override fun onDisabled(context: Context) {
        Log.d("XauUsdWidgetProvider", "Widget onDisabled - stopping background service to save battery")
        val serviceIntent = Intent(context, XauUsdWidgetService::class.java)
        context.stopService(serviceIntent)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d("XauUsdWidgetProvider", "onReceive: Action = ${intent.action}")
        
        if (intent.action == ACTION_REFRESH || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            startLiveService(context)
        }
    }

    private fun startLiveService(context: Context) {
        val serviceIntent = Intent(context, XauUsdWidgetService::class.java).apply {
            action = XauUsdWidgetService.ACTION_START_SYNC
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("XauUsdWidgetProvider", "Failed to start Foreground Service: ${e.message}")
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.widget.ACTION_REFRESH"

        fun updateAppWidgetDefault(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, AppR.layout.widget_layout)

            // Click pending intent for opening main app
            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(
                context,
                0,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(AppR.id.widget_root, appPendingIntent)

            // Click pending intent for manual refresh button
            val refreshIntent = Intent(context, XauUsdWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(AppR.id.widget_refresh_btn, refreshPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
