package com.powerledger.app

object CategoryClassifier {

    private val foodKeywords = listOf(
        "餐", "饭", "面", "咖啡", "奶茶", "饮", "外卖",
        "饿了么", "美团", "瑞幸", "麦当劳", "肯德基",
        "居酒屋", "拉面", "便当", "小吃", "火锅"
    )

    private val transportKeywords = listOf(
        "地铁", "公交", "打车", "滴滴", "高铁", "火车",
        "出行", "油", "停车", "电车", "suica", "pasmo", "icoca", "出租"
    )

    private val dailyKeywords = listOf(
        "便利", "全家", "罗森", "7-11", "7eleven",
        "超市", "菜", "生鲜", "日用", "药妆", "松本清", "百货"
    )

    private val shoppingKeywords = listOf(
        "淘宝", "京东", "拼多多", "商城", "服饰", "数码",
        "购物", "亚马逊", "amazon", "乐天", "旗舰店"
    )

    fun classify(text: String, amount: Double): String {
        val lower = text.lowercase()
        if (foodKeywords.any { lower.contains(it) }) return "餐饮"
        if (transportKeywords.any { lower.contains(it) }) return "交通"
        if (dailyKeywords.any { lower.contains(it) }) return "日用便利"
        if (shoppingKeywords.any { lower.contains(it) }) return "购物"

        return when {
            amount <= 35.0 -> "餐饮"
            amount <= 150.0 -> "日用便利"
            else -> "购物"
        }
    }
}
