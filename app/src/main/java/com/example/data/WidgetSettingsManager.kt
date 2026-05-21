package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import java.util.UUID

object WidgetSettingsManager {
    private const val PREFS_NAME = "XauUsdWidgetPrefs"
    private const val KEY_INTERVAL = "update_interval_sec"
    private const val KEY_LIVE_SYNC = "live_sync_enabled"
    private const val KEY_HISTORY = "price_history"
    private const val KEY_LAST_PRICE = "last_price"
    private const val KEY_PREV_CLOSE = "prev_close"
    private const val KEY_HIGH_24H = "high_24h"
    private const val KEY_LOW_24H = "low_24h"
    private const val KEY_ALARMS = "price_alarms"
    private const val KEY_PRICING_SOURCE = "pricing_source"
    private const val KEY_MICRO_FLUC = "micro_fluctuation_enabled"

    private const val MAX_HISTORY_POINTS = 20

    // In-memory cache for fast access in service loops
    @Volatile
    private var priceHistoryCache: List<Double> = emptyList()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getUpdateIntervalSec(context: Context): Int {
        return getPrefs(context).getInt(KEY_INTERVAL, 2) // default is 2s
    }

    fun setUpdateIntervalSec(context: Context, interval: Int) {
        getPrefs(context).edit().putInt(KEY_INTERVAL, interval).apply()
    }

    fun isLiveSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LIVE_SYNC, true) // default is true
    }

    fun setLiveSyncEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LIVE_SYNC, enabled).apply()
    }

    fun getPricingSource(context: Context): String {
        return getPrefs(context).getString(KEY_PRICING_SOURCE, "TRADING_VIEW") ?: "TRADING_VIEW"
    }

    fun setPricingSource(context: Context, source: String) {
        getPrefs(context).edit().putString(KEY_PRICING_SOURCE, source).apply()
    }

    @Synchronized
    fun getPriceHistory(context: Context): List<Double> {
        if (priceHistoryCache.isNotEmpty()) {
            return priceHistoryCache
        }
        val historyStr = getPrefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(historyStr)
            val list = mutableListOf<Double>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getDouble(i))
            }
            priceHistoryCache = list
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addPriceToHistory(context: Context, price: Double) {
        val currentHistory = getPriceHistory(context).toMutableList()
        currentHistory.add(price)
        while (currentHistory.size > MAX_HISTORY_POINTS) {
            currentHistory.removeAt(0)
        }
        priceHistoryCache = currentHistory

        // Save to SharedPreferences
        try {
            val jsonArray = JSONArray()
            currentHistory.forEach { jsonArray.put(it) }
            getPrefs(context).edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e("WidgetSettingsManager", "Error saving history: ${e.message}")
        }
    }

    fun getLastPrice(context: Context): Double {
        val priceStr = getPrefs(context).getString(KEY_LAST_PRICE, "0.0")
        return priceStr?.toDoubleOrNull() ?: 0.0
    }

    fun setLastPrice(context: Context, price: Double) {
        getPrefs(context).edit().putString(KEY_LAST_PRICE, price.toString()).apply()
        addPriceToHistory(context, price)
    }

    fun getPrevClosePrice(context: Context): Double {
        val priceStr = getPrefs(context).getString(KEY_PREV_CLOSE, "0.0")
        return priceStr?.toDoubleOrNull() ?: 0.0
    }

    fun setPrevClosePrice(context: Context, price: Double) {
        getPrefs(context).edit().putString(KEY_PREV_CLOSE, price.toString()).apply()
    }

    fun getHigh24h(context: Context): Double {
        val priceStr = getPrefs(context).getString(KEY_HIGH_24H, "0.0")
        return priceStr?.toDoubleOrNull() ?: 0.0
    }

    fun setHigh24h(context: Context, price: Double) {
        getPrefs(context).edit().putString(KEY_HIGH_24H, price.toString()).apply()
    }

    fun getLow24h(context: Context): Double {
        val priceStr = getPrefs(context).getString(KEY_LOW_24H, "0.0")
        return priceStr?.toDoubleOrNull() ?: 0.0
    }

    fun setLow24h(context: Context, price: Double) {
        getPrefs(context).edit().putString(KEY_LOW_24H, price.toString()).apply()
    }

    @Synchronized
    fun getAlarms(context: Context): List<PriceAlarm> {
        val prefs = getPrefs(context)
        val alarmsStr = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(alarmsStr)
            val list = mutableListOf<PriceAlarm>()
            for (i in 0 until jsonArray.length()) {
                list.add(PriceAlarm.fromJsonObject(jsonArray.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveAlarms(context: Context, alarms: List<PriceAlarm>) {
        try {
            val jsonArray = JSONArray()
            alarms.forEach { jsonArray.put(it.toJsonObject()) }
            getPrefs(context).edit().putString(KEY_ALARMS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e("WidgetSettingsManager", "Error saving alarms: ${e.message}")
        }
    }

    fun addAlarm(context: Context, targetPrice: Double, isAbove: Boolean, alarmType: String): PriceAlarm {
        val alarms = getAlarms(context).toMutableList()
        val newAlarm = PriceAlarm(
            id = UUID.randomUUID().toString(),
            targetPrice = targetPrice,
            isAbove = isAbove,
            alarmType = alarmType,
            state = "ACTIVE",
            createdAt = System.currentTimeMillis()
        )
        alarms.add(newAlarm)
        saveAlarms(context, alarms)
        return newAlarm
    }

    fun updateAlarmState(context: Context, id: String, newState: String, executedAt: Long? = null) {
        val alarms = getAlarms(context).map {
            if (it.id == id) {
                it.copy(state = newState, executedAt = executedAt ?: it.executedAt)
            } else {
                it
            }
        }
        saveAlarms(context, alarms)
    }

    fun deleteAlarm(context: Context, id: String) {
        val alarms = getAlarms(context).map {
            if (it.id == id) {
                it.copy(state = "DELETED")
            } else {
                it
            }
        }
        saveAlarms(context, alarms)
    }

    fun removeAlarmPermanently(context: Context, id: String) {
        val alarms = getAlarms(context).filter { it.id != id }
        saveAlarms(context, alarms)
    }

    fun resetAllAlarms(context: Context) {
        saveAlarms(context, emptyList())
    }

    fun isMicroFluctuationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MICRO_FLUC, true)
    }

    fun setMicroFluctuationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MICRO_FLUC, enabled).apply()
    }
}
