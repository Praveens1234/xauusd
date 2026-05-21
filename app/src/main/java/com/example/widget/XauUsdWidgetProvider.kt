package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R as AppR
import com.example.data.WidgetSettingsManager
import com.example.services.XauUsdWidgetService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class XauUsdWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d("XauUsdWidgetProvider", "onUpdate called for ${appWidgetIds.size} widgets")
        
        // Always try to start the Foreground Service to keep it alive or refresh
        startLiveService(context)
        
        // Also perform an initial basic draw update instantly using cached values
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
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
        var startedSuccessfully = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            startedSuccessfully = true
        } catch (e: Exception) {
            Log.e("XauUsdWidgetProvider", "Foreground service background start disallowed on modern Android. Executing one-time fetch fallback: ${e.message}")
        }

        // FALLBACK: If starting the service is blocked because the app is in the background,
        // spawn a quick asynchronous Coroutine to fetch pricing, check alarms, and update widgets.
        if (!startedSuccessfully) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val currentPrice = com.example.network.GoldPriceFetcher.fetchLivePrice(context)
                    if (currentPrice != null && currentPrice > 0.0) {
                        WidgetSettingsManager.setLastPrice(context, currentPrice)
                        
                        // Check alarms robustly
                        com.example.data.PriceAlarmEngine.checkAndTriggerAlarms(context, currentPrice)
                        
                        // Try to get updated statistics in background
                        try {
                            val stats = com.example.network.GoldPriceFetcher.fetch24hStats()
                            if (stats != null) {
                                WidgetSettingsManager.setPrevClosePrice(context, stats.open)
                                WidgetSettingsManager.setHigh24h(context, stats.high)
                                WidgetSettingsManager.setLow24h(context, stats.low)
                            }
                        } catch (stEx: Exception) {
                            Log.e("XauUsdWidgetProvider", "Background statistics refresh failed: ${stEx.message}")
                        }

                        // Re-draw all widgets with the new price
                        updateAllWidgets(context)
                    }
                } catch (ce: Exception) {
                    Log.e("XauUsdWidgetProvider", "Error during fallback update flow: ${ce.message}")
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.widget.ACTION_REFRESH"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, XauUsdWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (widgetId in allWidgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId)
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, AppR.layout.widget_layout)

            val lastPrice = WidgetSettingsManager.getLastPrice(context)
            val prevClose = WidgetSettingsManager.getPrevClosePrice(context)
            val history = WidgetSettingsManager.getPriceHistory(context)
            
            // Calculate dynamic trend percentage
            val diffPercent = if (prevClose > 0.0) {
                ((lastPrice - prevClose) / prevClose) * 100.0
            } else {
                0.0
            }

            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val lastUpdateTimeStr = timeFormat.format(Date())
            val intervalSec = WidgetSettingsManager.getUpdateIntervalSec(context)

            // Setup price string
            views.setTextViewText(AppR.id.widget_price, String.format(Locale.US, "$%,.2f", lastPrice))
            
            // Format dynamic trend text
            val trendText = String.format(Locale.US, "%s%.2f%%", if (diffPercent >= 0) "+" else "", diffPercent)
            views.setTextViewText(AppR.id.widget_change, trendText)

            // Colorize based on gains or losses
            if (diffPercent >= 0) {
                views.setInt(AppR.id.widget_change, "setBackgroundResource", AppR.drawable.badge_green)
                views.setTextColor(AppR.id.widget_live_status, Color.parseColor("#4CAF50"))
            } else {
                views.setInt(AppR.id.widget_change, "setBackgroundResource", AppR.drawable.badge_red)
                views.setTextColor(AppR.id.widget_live_status, Color.parseColor("#EF5350"))
            }

            // Sync Status footer
            views.setTextViewText(
                AppR.id.widget_timestamp,
                "Live Ticker: $lastUpdateTimeStr (${intervalSec}s)"
            )

            // Draw sparkline and inject it to ImageView
            val sparklineBitmap = drawSparklineBitmap(history, 200, 96)
            if (sparklineBitmap != null) {
                views.setImageViewBitmap(AppR.id.widget_sparkline, sparklineBitmap)
            }

            // Click pending intent for opening main app
            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(
                context,
                0,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(AppR.id.widget_root, appPendingIntent)

            // Click pending intent for manual refresh button directly hitting the service!
            // Direct launch by the OS from user input allows foreground start exemptions on Android 12+!
            val refreshIntent = Intent(context, XauUsdWidgetService::class.java).apply {
                action = XauUsdWidgetService.ACTION_START_SYNC
            }
            val refreshPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    appWidgetId,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context,
                    appWidgetId,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(AppR.id.widget_refresh_btn, refreshPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun drawSparklineBitmap(history: List<Double>, width: Int, height: Int): Bitmap? {
            if (history.size < 2) return null
            return try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val min = history.minOrNull() ?: 0.0
                val max = history.maxOrNull() ?: 1.0
                val range = if (max == min) 1.0 else max - min

                val isUptrend = history.last() >= history.first()
                val paintColor = if (isUptrend) Color.parseColor("#4CAF50") else Color.parseColor("#EF5350")

                // Main path line paint
                val paint = Paint().apply {
                    color = paintColor
                    style = Paint.Style.STROKE
                    strokeWidth = 4.5f
                    isAntiAlias = true
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }

                // Background glow paint
                val fillPaint = Paint().apply {
                    color = if (isUptrend) Color.parseColor("#154CAF50") else Color.parseColor("#15EF5350")
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }

                val path = Path()
                val fillPath = Path()
                val stepX = width.toFloat() / (history.size - 1)

                for (i in history.indices) {
                    val x = i * stepX
                    val normalizedY = ((history[i] - min) / range).toFloat()
                    val y = height.toFloat() - (normalizedY * (height - 16f) + 8f)
                    
                    if (i == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, height.toFloat())
                        fillPath.lineTo(x, y)
                    } else {
                        path.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                
                fillPath.lineTo((history.size - 1) * stepX, height.toFloat())
                fillPath.close()

                canvas.drawPath(fillPath, fillPaint)
                canvas.drawPath(path, paint)

                bitmap
            } catch (e: Exception) {
                Log.e("WidgetSparkline", "Error drawing sparkline: ${e.message}")
                null
            }
        }
    }
}
