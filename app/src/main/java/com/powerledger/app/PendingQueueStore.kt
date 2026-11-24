package com.powerledger.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class ExpenseRecord(
    val id: String = UUID.randomUUID().toString(),
    val amount: Double,
    val merchant: String,
    val category: String,
    val channel: String,
    val timestamp: Long,
    val dedupKey: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("amount", amount)
        put("merchant", merchant)
        put("category", category)
        put("channel", channel)
        put("timestamp", timestamp)
        put("dedupKey", dedupKey)
        put("source", "notification")
    }

    fun formattedAmount(): String {
        return if (amount % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f", amount)
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
    }
}

object PendingQueueStore {
    private const val PREFS_NAME = "power_ledger_queue_prefs"
    private const val KEY_QUEUE = "pending_records_json"
    private const val KEY_DEDUP_KEYS = "recent_dedup_keys_json"
    private const val MAX_DEDUP_HISTORY = 120

    @Synchronized
    fun enqueueIfNew(context: Context, record: ExpenseRecord): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dedupArray = JSONArray(prefs.getString(KEY_DEDUP_KEYS, "[]") ?: "[]")
        for (i in 0 until dedupArray.length()) {
            if (dedupArray.optString(i) == record.dedupKey) {
                return false
            }
        }

        val queueArray = JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]")
        queueArray.put(record.toJson())

        val newDedup = JSONArray()
        val startIdx = (dedupArray.length() - (MAX_DEDUP_HISTORY - 1)).coerceAtLeast(0)
        for (i in startIdx until dedupArray.length()) {
            newDedup.put(dedupArray.optString(i))
        }
        newDedup.put(record.dedupKey)

        prefs.edit()
            .putString(KEY_QUEUE, queueArray.toString())
            .putString(KEY_DEDUP_KEYS, newDedup.toString())
            .apply()
        return true
    }

    @Synchronized
    fun removeById(context: Context, recordId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val queueArray = JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]")
        val updated = JSONArray()
        var removed = false

        for (i in 0 until queueArray.length()) {
            val item = queueArray.optJSONObject(i) ?: continue
            if (item.optString("id") == recordId) {
                removed = true
            } else {
                updated.put(item)
            }
        }

        if (removed) {
            prefs.edit().putString(KEY_QUEUE, updated.toString()).apply()
        }
        return removed
    }

    @Synchronized
    fun pullAndClearAll(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        if (json != "[]") {
            prefs.edit().putString(KEY_QUEUE, "[]").apply()
        }
        return json
    }
}
