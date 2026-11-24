package com.powerledger.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 轻量事件驱动无障碍服务：
 * - 通过 accessibility_service_config.xml 严格限定仅监听微信与支付宝包名
 * - 无任何后台轮询线程，仅在支付成功页面出现时提取金额与商户
 */
class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        private const val PKG_WECHAT = "com.tencent.mm"
        private const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

        private val SUCCESS_INDICATORS = listOf(
            "支付成功", "付款成功", "支付完成", "交易成功", "已支付"
        )

        private val EXCLUDE_INDICATORS = listOf(
            "已收款", "收款到账", "退款", "红包", "转入", "收益", "还款", "账单明细", "全部账单"
        )

        private val UI_NOISE = setOf(
            "支付成功", "付款成功", "支付完成", "交易成功", "完成", "确定", "返回",
            "微信支付", "支付宝", "支付方式", "零钱", "零钱通", "优惠", "商家",
            "收款方", "付款方式", "订单信息", "查看详情", "电子发票", "积分", "摇一摇"
        )

        private val CURRENCY_REGEX = Regex(
            "(?:¥|￥)\\s*([0-9]+(?:\\.[0-9]{1,2})?)|([0-9]+(?:\\.[0-9]{1,2})?)\\s*元"
        )
    }

    private var lastScanTimeMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != PKG_WECHAT && pkg != PKG_ALIPAY) return

        val now = System.currentTimeMillis()
        if (now - lastScanTimeMs < 400L) return
        lastScanTimeMs = now

        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>(48)
        collectTexts(root, texts, maxNodes = 220)
        if (texts.isEmpty()) return

        val combined = texts.joinToString(" ")
        if (SUCCESS_INDICATORS.none { combined.contains(it) }) return
        if (EXCLUDE_INDICATORS.any { combined.contains(it) }) return

        val amount = extractAmount(texts) ?: return
        if (amount <= 0.0) return

        val channel = if (pkg == PKG_WECHAT) "微信" else "支付宝"
        val merchant = extractMerchant(texts, channel)
        val category = CategoryClassifier.classify("$merchant $combined", amount)

        val formattedAmt = String.format(Locale.US, "%.2f", amount)
        val bucket = now / 20_000L
        val dedupKey = "a11y_${bucket}_${pkg}_${formattedAmt}"

        val record = ExpenseRecord(
            amount = amount,
            merchant = merchant,
            category = category,
            channel = channel,
            timestamp = now,
            dedupKey = dedupKey
        )

        if (PendingQueueStore.enqueueIfNew(applicationContext, record)) {
            FloatingReceiptCard.show(applicationContext, record)
        }
    }

    override fun onInterrupt() {
        // 无需后台清理
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, maxNodes: Int) {
        if (out.size >= maxNodes) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) {
            out.add(t)
        } else {
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty()) {
                out.add(desc)
            }
        }
        val count = node.childCount
        for (i in 0 until count) {
            if (out.size >= maxNodes) break
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, maxNodes)
        }
    }

    private fun extractAmount(texts: List<String>): Double? {
        for (item in texts) {
            val match = CURRENCY_REGEX.find(item)
            if (match != null) {
                val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val value = raw.toDoubleOrNull()
                if (value != null && value > 0.0) return value
            }
        }
        // 兼容微信支付成功页单独一行纯数字（如 "25.58"）且前一行是 "￥" 或 "¥"
        for (i in 0 until texts.size - 1) {
            if (texts[i] == "¥" || texts[i] == "￥") {
                val value = texts[i + 1].toDoubleOrNull()
                if (value != null && value > 0.0) return value
            }
        }
        return null
    }

    private fun extractMerchant(texts: List<String>, channel: String): String {
        val priceIdx = texts.indexOfFirst {
            CURRENCY_REGEX.containsMatchIn(it) || it == "¥" || it == "￥"
        }
        val searchRange = if (priceIdx > 0) {
            texts.subList(0, priceIdx).asReversed()
        } else {
            texts
        }

        for (candidate in searchRange) {
            val clean = candidate.trim()
            if (clean.length in 2..24 &&
                clean !in UI_NOISE &&
                !clean.startsWith("¥") &&
                !clean.startsWith("￥") &&
                !clean.matches(Regex("^[0-9.]+$")) &&
                !clean.contains("支付") &&
                !clean.contains("优惠")
            ) {
                return clean
            }
        }
        return "${channel}消费"
    }
}
