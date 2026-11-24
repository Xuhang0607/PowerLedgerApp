package com.powerledger.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

/**
 * 纯事件驱动的通知监听服务：
 * - 仅监听微信 (com.tencent.mm) 与支付宝 (com.eg.android.AlipayGphone)
 * - 禁止任何后台轮询线程与无障碍服务
 */
class PaymentNotificationService : NotificationListenerService() {

    companion object {
        private const val PKG_WECHAT = "com.tencent.mm"
        private const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

        private val TARGET_PACKAGES = setOf(PKG_WECHAT, PKG_ALIPAY)

        private val INCLUDE_KEYWORDS = listOf(
            "支付", "付款", "扣款", "消费", "支出", "¥", "￥", "元"
        )

        private val EXCLUDE_KEYWORDS = listOf(
            "已收款", "收款到账", "退款", "红包", "转入", "收益", "还款"
        )

        // 优先匹配带货币符号或“元”的金额，其次匹配支付动词后的数字
        private val CURRENCY_AMOUNT_REGEX = Regex(
            "(?:¥|￥|RMB|人民币)\\s*([0-9]+(?:\\.[0-9]{1,2})?)|([0-9]+(?:\\.[0-9]{1,2})?)\\s*元"
        )
        private val VERB_AMOUNT_REGEX = Regex(
            "(?:支付|付款|扣款|消费|支出)\\D{0,6}([0-9]+(?:\\.[0-9]{1,2})?)"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg !in TARGET_PACKAGES) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        val combined = listOf(title, text, bigText, subText)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")

        if (combined.isBlank()) return
        if (INCLUDE_KEYWORDS.none { combined.contains(it) }) return
        if (EXCLUDE_KEYWORDS.any { combined.contains(it) }) return

        val amount = extractAmount(combined) ?: return
        if (amount <= 0.0) return

        val channel = if (pkg == PKG_WECHAT) "微信" else "支付宝"
        val merchant = extractMerchant(title, combined, channel)
        val category = CategoryClassifier.classify("$merchant $combined", amount)
        val timestamp = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()
        val formattedAmt = String.format(Locale.US, "%.2f", amount)
        val dedupKey = "${timestamp}_${pkg}_${formattedAmt}"

        val record = ExpenseRecord(
            amount = amount,
            merchant = merchant,
            category = category,
            channel = channel,
            timestamp = timestamp,
            dedupKey = dedupKey
        )

        if (PendingQueueStore.enqueueIfNew(applicationContext, record)) {
            FloatingReceiptCard.show(applicationContext, record)
        }
    }

    private fun extractAmount(text: String): Double? {
        val currencyMatch = CURRENCY_AMOUNT_REGEX.find(text)
        if (currencyMatch != null) {
            val raw = currencyMatch.groupValues[1].ifEmpty { currencyMatch.groupValues[2] }
            raw.toDoubleOrNull()?.let { return it }
        }
        val verbMatch = VERB_AMOUNT_REGEX.find(text)
        if (verbMatch != null) {
            verbMatch.groupValues[1].toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun extractMerchant(title: String, fullText: String, channel: String): String {
        val patterns = listOf(
            Regex("(?:向|给|在)([^\\s，,。！!：:支付付款消费扣款]{2,20}?)(?:付款|支付|消费|扣款)"),
            Regex("商户[:：]\\s*([^\\s，,。]+)")
        )
        for (regex in patterns) {
            val match = regex.find(fullText)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotEmpty() && candidate !in listOf("微信支付", "支付宝", "微信")) {
                    return candidate
                }
            }
        }
        val cleanTitle = title
            .replace("微信支付", "")
            .replace("支付宝", "")
            .replace("支付成功", "")
            .replace("付款成功", "")
            .trim()
        return if (cleanTitle.length >= 2) cleanTitle.take(20) else "${channel}消费"
    }
}
