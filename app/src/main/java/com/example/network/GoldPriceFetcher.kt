package com.example.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object GoldPriceFetcher {
    private const val TAG = "GoldPriceFetcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches the live physical XAU/USD Spot Gold price in USD.
     * Tries the authentic spot gold API first, falls back to Binance/Coinbase PAXGUSDT if needed.
     */
    suspend fun fetchLivePrice(): Double? {
        // Method 1: Authentic Spot XAUUSD Gold API
        try {
            val price = fetchFromGoldApi()
            if (price != null && price > 0) {
                return price
            }
        } catch (e: Exception) {
            Log.e(TAG, "Authentic Spot XAU/USD API fetch failed: ${e.message}")
        }

        // Method 2: Binance PAXGUSDT Fallback
        try {
            val price = fetchFromBinance()
            if (price != null && price > 0) {
                return price
            }
        } catch (e: Exception) {
            Log.e(TAG, "Binance PAXGUSDT fallback fetch failed: ${e.message}")
        }

        // Method 3: Coinbase PAXG Fallback
        try {
            val price = fetchFromCoinbase()
            if (price != null && price > 0) {
                return price
            }
        } catch (e: Exception) {
            Log.e(TAG, "Coinbase PAXG fallback fetch failed: ${e.message}")
        }

        return null
    }

    private fun fetchFromGoldApi(): Double? {
        val request = Request.Builder()
            .url("https://api.gold-api.com/price/XAU")
            .header("User-Agent", "GoldLiveAppWidget/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyString = response.body?.string() ?: return null
            val json = JSONObject(bodyString)
            return if (json.has("price")) {
                json.optDouble("price")
            } else {
                null
            }
        }
    }

    private fun fetchFromBinance(): Double? {
        val request = Request.Builder()
            .url("https://api.binance.com/api/v3/ticker/price?symbol=PAXGUSDT")
            .header("User-Agent", "GoldLiveAppWidget/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyString = response.body?.string() ?: return null
            
            // Expected JSON: {"symbol":"PAXGUSDT","price":"2315.42000000"}
            val json = JSONObject(bodyString)
            val priceStr = json.optString("price")
            return priceStr.toDoubleOrNull()
        }
    }

    private fun fetchFromCoinbase(): Double? {
        val request = Request.Builder()
            .url("https://api.coinbase.com/v2/prices/PAXG-USD/spot")
            .header("User-Agent", "GoldLiveAppWidget/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyString = response.body?.string() ?: return null
            
            // Expected JSON: {"data":{"base":"PAXG","currency":"USD","amount":"2365.42"}}
            val json = JSONObject(bodyString)
            val data = json.optJSONObject("data") ?: return null
            val amountStr = data.optString("amount")
            return amountStr.toDoubleOrNull()
        }
    }

    data class Stats24h(val open: Double, val high: Double, val low: Double, val changePercent: Double)

    suspend fun fetch24hStats(): Stats24h? {
        // We use Binance for detailed stats which is extremely rich
        return try {
            val request = Request.Builder()
                .url("https://api.binance.com/api/v3/ticker/24hr?symbol=PAXGUSDT")
                .header("User-Agent", "GoldLiveAppWidget/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val json = JSONObject(bodyString)
                val paxgPrice = json.optDouble("lastPrice", 0.0)
                val open = json.optDouble("openPrice", 0.0)
                val high = json.optDouble("highPrice", 0.0)
                val low = json.optDouble("lowPrice", 0.0)
                val percent = json.optDouble("priceChangePercent", 0.0)

                // Calibration ratio to map Crypto (PAXG) to direct physical spot XAUUSD
                val spotPrice = fetchFromGoldApi()
                if (spotPrice != null && spotPrice > 0.0 && paxgPrice > 0.0) {
                    val ratio = spotPrice / paxgPrice
                    Stats24h(
                        open = open * ratio,
                        high = high * ratio,
                        low = low * ratio,
                        changePercent = percent
                    )
                } else {
                    Stats24h(open, high, low, percent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch 24h stats: ${e.message}")
            null
        }
    }
}
