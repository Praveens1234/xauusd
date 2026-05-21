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
        
        // Show Foreground Notification with targetSdk 34+ requirement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        // Force an immediate draw using cached data to avoid launcher out-of-date APK paths
        updateWidgets()

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
                        val currentPrice = GoldPriceFetcher.fetchLivePrice(this@XauUsdWidgetService)
                        if (currentPrice != null) {
                            WidgetSettingsManager.setLastPrice(this@XauUsdWidgetService, currentPrice)
                            
                            // Live evaluation of active price alarms using robust engine
                            com.example.data.PriceAlarmEngine.checkAndTriggerAlarms(this@XauUsdWidgetService, currentPrice)
                            
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

        XauUsdWidgetProvider.updateAllWidgets(context)
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
