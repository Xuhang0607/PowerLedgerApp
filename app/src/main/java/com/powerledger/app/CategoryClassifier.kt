package com.powerledger.app

object CategoryClassifier {

    private val billKeywords = listOf(
        "话费", "充值", "中国移动", "中国联通", "中国电信", "物业", "水费", "电费", "燃气", "宽带", "供暖", "社保"
    )

    private val entertainmentKeywords = listOf(
        "烫发", "染发", "美发", "理发", "沙龙", "美甲", "滑雪", "门票", "影院", "电影",
        "ktv", "游乐", "景区", "健身", "按摩", "足浴", "网费", "会员", "游戏", "网络"
    )

    private val transportKeywords = listOf(
        "地铁", "公交", "打车", "滴滴", "高德打车", "花小猪", "高铁", "火车", "12306",
        "出行", "加油", "停车", "高速", "电车", "suica", "pasmo", "icoca", "出租", "单车"
    )

    private val foodKeywords = listOf(
        "外卖", "闪购", "小时达", "餐", "饭", "面", "粉", "米线", "麻辣", "香锅", "火锅", "涮",
        "烧烤", "烤肉", "炸鸡", "汉堡", "披萨", "必胜客", "麦当劳", "肯德基", "瑞幸", "星巴克",
        "咖啡", "奶茶", "茶饮", "饮品", "饮", "甜品", "蛋糕", "面包", "零食", "好想来",
        "居酒屋", "拉面", "便当", "小吃", "烩面", "拌面", "堡", "饿了么", "食堂", "早餐", "夜宵"
    )

    private val dailyKeywords = listOf(
        "便利", "全家", "罗森", "7-11", "7eleven", "超市", "菜", "生鲜", "水果", "买菜",
        "日用", "药妆", "药房", "药店", "松本清", "百货", "商贸", "洗护", "纸巾"
    )

    private val transferKeywords = listOf(
        "微信转账", "支付宝转账", "转账", "确认收款", "待朋友收款", "扫二维码付款", "二维码收款", "发红包", "微信红包", "亲属卡"
    )

    private val platformKeywords = listOf(
        "淘宝", "京东", "拼多多", "商城", "旗舰店", "服饰", "数码", "亚马逊", "amazon", "乐天", "美团", "抖音"
    )

    fun classify(text: String, amount: Double): String {
        val lower = text.lowercase()
        if (billKeywords.any { lower.contains(it) }) return "生活缴费"
        if (entertainmentKeywords.any { lower.contains(it) }) return "休闲娱乐"
        if (transportKeywords.any { lower.contains(it) }) return "交通"
        if (foodKeywords.any { lower.contains(it) }) return "餐饮"
        if (dailyKeywords.any { lower.contains(it) }) return "日用便利"
        if (transferKeywords.any { lower.contains(it) }) return "转账人情"
        if (platformKeywords.any { lower.contains(it) }) {
            return if (amount <= 35.0) "餐饮" else "购物"
        }
        return when {
            amount <= 35.0 -> "餐饮"
            amount <= 150.0 -> "日用便利"
            else -> "购物"
        }
    }
}
