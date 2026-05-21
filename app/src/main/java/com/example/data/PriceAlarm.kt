package com.example.data

import org.json.JSONObject

data class PriceAlarm(
    val id: String,
    val targetPrice: Double,
    val isAbove: Boolean, // true if trigger when price is above target, false if below
    val alarmType: String, // "SIMPLE" or "SOUND_VIB"
    val state: String,  // "ACTIVE", "DEACTIVATED", "EXECUTED", "DELETED"
    val createdAt: Long,
    val executedAt: Long? = null
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("targetPrice", targetPrice)
        obj.put("isAbove", isAbove)
        obj.put("alarmType", alarmType)
        obj.put("state", state)
        obj.put("createdAt", createdAt)
        if (executedAt != null) {
            obj.put("executedAt", executedAt)
        }
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): PriceAlarm {
            return PriceAlarm(
                id = obj.getString("id"),
                targetPrice = obj.getDouble("targetPrice"),
                isAbove = obj.getBoolean("isAbove"),
                alarmType = obj.optString("alarmType", "SIMPLE"),
                state = obj.optString("state", "ACTIVE"),
                createdAt = obj.getLong("createdAt"),
                executedAt = if (obj.has("executedAt")) obj.getLong("executedAt") else null
            )
        }
    }
}
