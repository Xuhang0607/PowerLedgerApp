package com.powerledger.app

import java.util.Locale

data class ParsedBill(
    val amount: Double,
    val merchant: String,
    val category: String,
    val channel: String,
    val dedupKey: String
)

/**
 * 可扩展的多 App 账单适配器接口：
 * 后续适配支付宝、云闪付、美团、京东等只需实现此接口并注册到 AdapterRegistry。
 */
interface AppBillAdapter {
    val packageName: String
    val channelName: String
    fun parse(texts: List<String>): ParsedBill?
}

object AdapterRegistry {
    private val adapters: Map<String, AppBillAdapter> = listOf(
        WeChatBillAdapter
    ).associateBy { it.packageName }

    fun get(packageName: String): AppBillAdapter? = adapters[packageName]
}

object WeChatBillAdapter : AppBillAdapter {
    override val packageName: String = "com.tencent.mm"
    override val channelName: String = "微信"

    private val SUCCESS_INDICATORS = listOf(
        "支付成功", "付款成功", "支付完成", "付款完成", "交易成功", "已支付", "已付款",
        "转账成功", "待朋友收款", "已转账", "确认收款"
    )

    // 仿照钱迹：发完红包后再次点开红包详情页可识别
    private val SENT_REDPACKET_INDICATORS = listOf(
        "等待对方领取", "个红包共", "红包金额"
    )

    private val EXCLUDE_INDICATORS = listOf(
        "已收款", "收款到账", "退款", "转入", "收益", "还款",
        "账单明细", "全部账单", "账单详情", "交易单号", "商户单号", "支付时间", "创建时间", "订单编号"
    )

    private val UI_NOISE = setOf(
        "支付成功", "付款成功", "支付完成", "付款完成", "交易成功", "已支付", "已付款",
        "转账成功", "待朋友收款", "已转账", "微信转账", "微信红包",
        "完成", "确定", "返回", "微信支付", "支付方式", "零钱", "零钱通",
        "优惠", "商家", "收款方", "付款方式", "订单信息", "查看详情", "电子发票", "积分", "摇一摇"
    )

    private val CURRENCY_REGEX = Regex(
        "(?:¥|￥)\\s*([0-9]+(?:\\.[0-9]{1,2})?)|([0-9]+(?:\\.[0-9]{1,2})?)\\s*元"
    )
    private val REDPACKET_TOTAL_REGEX = Regex("共\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*元")

    override fun parse(texts: List<String>): ParsedBill? {
        if (texts.isEmpty()) return null
        val combined = texts.joinToString(" ")

        if (EXCLUDE_INDICATORS.any { combined.contains(it) }) return null

        val isNormalPay = SUCCESS_INDICATORS.any { combined.contains(it) }
        val isSentRedPacket = !isNormalPay &&
            SENT_REDPACKET_INDICATORS.any { combined.contains(it) } &&
            combined.contains("的红包")

        if (!isNormalPay && !isSentRedPacket) return null

        val amount = if (isSentRedPacket) {
            REDPACKET_TOTAL_REGEX.find(combined)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: extractAmount(texts)
        } else {
            extractAmount(texts)
        } ?: return null

        if (amount <= 0.0) return null

        val merchant = if (isSentRedPacket) {
            "微信红包"
        } else {
            extractMerchant(texts)
        }

        val category = if (isSentRedPacket) {
            "转账人情"
        } else {
            CategoryClassifier.classify("$merchant $combined", amount)
        }

        val formattedAmt = String.format(Locale.US, "%.2f", amount)
        return ParsedBill(
            amount = amount,
            merchant = merchant,
            category = category,
            channel = channelName,
            dedupKey = "wx_${formattedAmt}"
        )
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
        for (i in 0 until texts.size - 1) {
            if (texts[i] == "¥" || texts[i] == "￥") {
                val value = texts[i + 1].toDoubleOrNull()
                if (value != null && value > 0.0) return value
            }
        }
        return null
    }

    private fun extractMerchant(texts: List<String>): String {
        val priceIdx = texts.indexOfFirst {
            CURRENCY_REGEX.containsMatchIn(it) || it == "¥" || it == "￥"
        }
        val searchRange = if (priceIdx > 0) texts.subList(0, priceIdx).asReversed() else texts

        for (candidate in searchRange) {
            val clean = candidate.trim()
            val waitMatch = Regex("^待(.{1,20}?)确认收款$").find(clean)
            if (waitMatch != null) {
                return "转账给" + waitMatch.groupValues[1].trim()
            }
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
        return "微信消费"
    }
}
