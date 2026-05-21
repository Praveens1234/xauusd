package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.WidgetSettingsManager
import com.example.network.GoldPriceFetcher
import com.example.widget.XauUsdWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class XauUsdWidgetService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var syncJob: Job? = null

    private var isScreenOn = true
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")
        createNotificationChannel()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: action = ${intent?.action}")
        
        // Show Foreground Notification
        startForeground(NOTIFICATION_ID, createNotification())

        if (intent?.action == ACTION_START_SYNC) {
            restartSyncEngine()
        }

        return START_STICKY
    }

    private fun restartSyncEngine() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            Log.d(TAG, "Starting sync engine loop...")
            
            // Initial stats fetch (do once on startup, then every 100 ticks)
            var statsTickCount = 0
            fetchAndStoreStats()

            while (isActive) {
                if (isScreenOn && WidgetSettingsManager.isLiveSyncEnabled(this@XauUsdWidgetService)) {
                    try {
                        val currentPrice = GoldPriceFetcher.fetchLivePrice()
                        if (currentPrice != null) {
                            val previousPrice = WidgetSettingsManager.getLastPrice(this@XauUsdWidgetService)
                            WidgetSettingsManager.setLastPrice(this@XauUsdWidgetService, currentPrice)
                            
                            // Live evaluation of active price alarms
                            checkPriceAlarms(previousPrice, currentPrice)
                            
                            // Every 30 loops (e.g. 1 minute if interval is 2s), refresh 24h stats
                            statsTickCount++
                            if (statsTickCount >= 30) {
                                statsTickCount = 0
                                fetchAndStoreStats()
                            }

                            // Update Widgets right away!
                            withContext(Dispatchers.Main) {
                                updateWidgets()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in service pricing fetch: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "Sync loop running but screen is off or live sync disabled.")
                }

                val intervalSec = WidgetSettingsManager.getUpdateIntervalSec(this@XauUsdWidgetService)
                delay(intervalSec * 1000L)
            }
        }
    }

    private suspend fun fetchAndStoreStats() {
        try {
            val stats = GoldPriceFetcher.fetch24hStats()
            if (stats != null) {
                // Update SharedPreferences
                WidgetSettingsManager.setPrevClosePrice(this@XauUsdWidgetService, stats.open)
                WidgetSettingsManager.setHigh24h(this@XauUsdWidgetService, stats.high)
                WidgetSettingsManager.setLow24h(this@XauUsdWidgetService, stats.low)
                Log.d(TAG, "Successfully updated 24h stock statistics: $stats")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update statistics: ${e.message}")
        }
    }

    private fun updateWidgets() {
        val context = this@XauUsdWidgetService
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisWidget = ComponentName(context, XauUsdWidgetProvider::class.java)
        val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

        if (allWidgetIds.isEmpty()) {
            Log.d(TAG, "No active widgets found, stopping background service.")
            stopSelf()
            return
        }

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

        for (widgetId in allWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Setup price string
            views.setTextViewText(R.id.widget_price, String.format(Locale.US, "$%,.2f", lastPrice))
            
            // Format dynamic trend text
            val trendText = String.format(Locale.US, "%s%.2f%%", if (diffPercent >= 0) "+" else "", diffPercent)
            views.setTextViewText(R.id.widget_change, trendText)

            // Colorize based on gains or losses
            if (diffPercent >= 0) {
                views.setInt(R.id.widget_change, "setBackgroundResource", R.drawable.badge_green)
                views.setTextColor(R.id.widget_live_status, Color.parseColor("#4CAF50"))
            } else {
                views.setInt(R.id.widget_change, "setBackgroundResource", R.drawable.badge_red)
                views.setTextColor(R.id.widget_live_status, Color.parseColor("#EF5350"))
            }

            // Sync Status footer
            views.setTextViewText(
                R.id.widget_timestamp,
                "Live Ticker: $lastUpdateTimeStr (${intervalSec}s)"
            )

            // Draw sparkline and inject it to ImageView
            val sparklineBitmap = drawSparklineBitmap(history, 200, 96)
            if (sparklineBitmap != null) {
                views.setImageViewBitmap(R.id.widget_sparkline, sparklineBitmap)
            }

            // Setup Pending Intents again so clicks always register
            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(
                context,
                0,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, appPendingIntent)

            val refreshIntent = Intent(context, XauUsdWidgetProvider::class.java).apply {
                action = XauUsdWidgetProvider.ACTION_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPendingIntent)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
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
                // Bottom margin of 8px, top margin of 8px
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
            
            // Complete fill shape to bottom edge
            fillPath.lineTo((history.size - 1) * stepX, height.toFloat())
            fillPath.close()

            // Draw shadow fill first, then paths
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(path, paint)

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing sparkline bitmap: ${e.message}")
            null
        }
    }

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "Screen Turned ON: Resuming Sync")
                        isScreenOn = true
                        restartSyncEngine()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Screen Turned OFF: Pausing Sync")
                        isScreenOn = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Gold Live Widget Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Gold (XAUUSD) prices updated in real time on the home screen."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val appIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gold Live Tracking Active")
            .setContentText("Sub-second real-time gold widget feeding is active.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setColor(Color.parseColor("#D4AF37"))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkPriceAlarms(oldPrice: Double, newPrice: Double) {
        val alarms = WidgetSettingsManager.getAlarms(this)
        val activeAlarms = alarms.filter { it.state == "ACTIVE" }
        if (activeAlarms.isEmpty()) return

        for (alarm in activeAlarms) {
            val triggered = if (alarm.isAbove) {
                newPrice >= alarm.targetPrice && (oldPrice < alarm.targetPrice || oldPrice == 0.0)
            } else {
                newPrice <= alarm.targetPrice && (oldPrice > alarm.targetPrice || oldPrice == 0.0)
            }

            if (triggered) {
                // Execute and trigger beautiful custom live notifications
                WidgetSettingsManager.updateAlarmState(this, alarm.id, "EXECUTED", System.currentTimeMillis())
                triggerNotification(alarm, newPrice)
            }
        }
    }

    private fun triggerNotification(alarm: com.example.data.PriceAlarm, currentPrice: Double) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = alarm.hashCode()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
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

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(titleText)
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_ALARM)

        if (alarm.alarmType == "SOUND_VIB") {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 1000))
                .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
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

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy called")
        syncJob?.cancel()
        serviceJob.cancel()
        screenReceiver?.let {
            unregisterReceiver(it)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XauUsdWidgetService"
        const val ACTION_START_SYNC = "com.example.services.ACTION_START_SYNC"
        private const val NOTIFICATION_ID = 54321
        private const val CHANNEL_ID = "XauUsdWidgetServiceChannel"
    }
}
