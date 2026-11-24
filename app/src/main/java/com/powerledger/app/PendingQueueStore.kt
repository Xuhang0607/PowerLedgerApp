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
    val dedupKey: String,
    val isRefund: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("amount", amount)
        put("merchant", merchant)
        put("category", category)
        put("channel", channel)
        put("timestamp", timestamp)
        put("dedupKey", dedupKey)
        put("isRefund", isRefund)
        put("source", if (isRefund) "refund_notification" else "auto")
    }

    fun formattedAmount(): String {
        val absAmt = kotlin.math.abs(amount)
        return if (absAmt % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f", absAmt)
        } else {
            String.format(Locale.US, "%.2f", absAmt)
        }
    }
}

object PendingQueueStore {
    private const val PREFS_NAME = "power_ledger_queue_prefs"
    private const val KEY_QUEUE = "pending_records_json"
    private const val KEY_RECENT_WINDOW = "recent_dedup_window_json"
    private const val KEY_DEBUG_LOGS = "debug_logs_json"
    private const val MAX_DEDUP_HISTORY = 100
    private const val DEDUP_WINDOW_MS = 20_000L

    @Synchronized
    fun appendDebugLog(context: Context, line: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_DEBUG_LOGS, "[]") ?: "[]")
        val next = JSONArray()
        val start = (arr.length() - 9).coerceAtLeast(0)
        for (i in start until arr.length()) {
            next.put(arr.optString(i))
        }
        next.put(line)
        prefs.edit().putString(KEY_DEBUG_LOGS, next.toString()).apply()
    }

    @Synchronized
    fun getDebugLogs(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEBUG_LOGS, "[]") ?: "[]"
    }

    /**
     * 跨通道（通知监听 + 无障碍）统一防重：
     * 同一包名 + 同一金额 + 同为支出/退款，在 20 秒窗口内只记一次。
     */
    @Synchronized
    fun enqueueIfNew(context: Context, record: ExpenseRecord): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recentArray = JSONArray(prefs.getString(KEY_RECENT_WINDOW, "[]") ?: "[]")
        val now = record.timestamp

        for (i in 0 until recentArray.length()) {
            val item = recentArray.optJSONObject(i) ?: continue
            val key = item.optString("key")
            val ts = item.optLong("ts", 0L)
            if (key == record.dedupKey && kotlin.math.abs(now - ts) < DEDUP_WINDOW_MS) {
                return false
            }
        }

        val queueArray = JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]")
        queueArray.put(record.toJson())

        val newRecent = JSONArray()
        val startIdx = (recentArray.length() - (MAX_DEDUP_HISTORY - 1)).coerceAtLeast(0)
        for (i in startIdx until recentArray.length()) {
            newRecent.put(recentArray.optJSONObject(i))
        }
        newRecent.put(JSONObject().apply {
            put("key", record.dedupKey)
            put("ts", now)
        })

        prefs.edit()
            .putString(KEY_QUEUE, queueArray.toString())
            .putString(KEY_RECENT_WINDOW, newRecent.toString())
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
