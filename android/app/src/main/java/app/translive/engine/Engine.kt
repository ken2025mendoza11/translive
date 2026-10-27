package app.translive.engine

enum class Layer { GLOSSARY, DICTIONARY, TEMPLATE, CACHE, NONE }

data class Hit(
    val zh: String,
    val en: String,
    val layer: Layer,
    val upload: Boolean,
    val pii: Boolean,
)

data class Fit(val fontPx: Float, val lines: Int, val ellipsis: Boolean)

object PiiFilter {
    private val phone = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val longId = Regex("\\d{8,}")
    private val tracking = Regex("\\b[A-Z]{2}\\d{9,}[A-Z]{2}\\b", RegexOption.IGNORE_CASE)

    fun hasPii(text: String): Boolean =
        phone.containsMatchIn(text) || email.containsMatchIn(text) ||
            longId.containsMatchIn(text) || tracking.containsMatchIn(text)
}

object TemplateEngine {
    private val templates: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("^剩(\\d+(?:\\.\\d+)?)张$") to { m -> "${m.groupValues[1]} left" },
        Regex("^满(\\d+(?:\\.\\d+)?)(?:可减|可用)$") to { m -> "On ¥${m.groupValues[1]}+" },
        Regex("^满(\\d+(?:\\.\\d+)?)减(\\d+(?:\\.\\d+)?)$") to { m -> "Spend ¥${m.groupValues[1]}, save ¥${m.groupValues[2]}" },
        Regex("^首件立减(\\d+(?:\\.\\d+)?)元$") to { m -> "¥${m.groupValues[1]} off first item" },
        Regex("^已拼(\\d+(?:\\.\\d+)?)万件$") to { m -> "${wanToK(m.groupValues[1])} sold" },
        Regex("^已拼(\\d+)件$") to { m -> "${m.groupValues[1].toInt()} sold" },
        Regex("^(\\d+(?:\\.\\d+)?)折$") to { m -> "${percentOff(m.groupValues[1])}% off" },
        Regex("^低至(\\d+(?:\\.\\d+)?)折$") to { m -> "From ${percentOff(m.groupValues[1])}% off" },
        Regex("^还差(\\d+)人$") to { m -> "${m.groupValues[1]} more needed" },
        Regex("^还差(\\d+(?:\\.\\d+)?)元包邮$") to { m -> "¥${m.groupValues[1]} more for free shipping" },
        Regex("^下场(\\d+)点开抢$") to { m -> "Next round ${m.groupValues[1]}:00" },
        Regex("^(\\d+)件起售$") to { m -> "Min ${m.groupValues[1]}" },
        Regex("^[¥￥](\\d+(?:\\.\\d+)?)$") to { m -> "¥${m.groupValues[1]}" },
        Regex("^(\\d+(?:\\.\\d+)?)元$") to { m -> "¥${m.groupValues[1]}" },
        Regex("^(\\d+(?:\\.\\d+)?)斤$") to { m -> "${m.groupValues[1]} jin (${kg(m.groupValues[1])} kg)" },
    )

    fun apply(zh: String): String? {
        for ((regex, run) in templates) {
            val match = regex.matchEntire(zh) ?: continue
            return run(match)
        }
        return null
    }

    private fun wanToK(raw: String): String {
        val k = raw.toDouble() * 10.0
        val rounded = kotlin.math.round(k * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) "${rounded.toInt()}K" else "${rounded}K"
    }

    private fun percentOff(raw: String): Int = kotlin.math.max(0, kotlin.math.round(100 - raw.toDouble() * 10).toInt())

    private fun kg(raw: String): String {
        val v = kotlin.math.round(raw.toDouble() * 0.5 * 100.0) / 100.0
        return if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
    }
}

object CurrencyFormat {
    val rates = mapOf("PHP" to 7.95, "USD" to 0.14, "EUR" to 0.13, "GBP" to 0.11)
    private val symbols = mapOf("PHP" to "₱", "USD" to "$", "EUR" to "€", "GBP" to "£")
    private val price = Regex("¥(\\d+(?:\\.\\d+)?)")

    fun apply(en: String, code: String): String {
        val rate = rates[code] ?: return en
        val symbol = symbols[code] ?: return en
        return price.replace(en) { m ->
            val local = kotlin.math.round(m.groupValues[1].toDouble() * rate).toInt()
            "¥${m.groupValues[1]} (≈$symbol$local)"
        }
    }
}

object Units {
    fun apply(zh: String, en: String): String {
        if (en.contains("kg)")) return en
        val jin = Regex("(\\d+(?:\\.\\d+)?)斤").find(zh)
        if (jin != null) {
            val kg = kotlin.math.round(jin.groupValues[1].toDouble() * 0.5 * 100.0) / 100.0
            return "$en ($kg kg)"
        }
        return en
    }
}

object LookupChain {
    fun translate(
        raw: String,
        glossary: Map<String, String>,
        dictionary: Map<String, String>,
        cache: Map<String, String>,
        currency: String,
        screenBlocked: Boolean,
    ): Hit {
        val zh = raw.trim()
        val pii = PiiFilter.hasPii(zh)
        val upload = !pii && !screenBlocked
        fun finish(en: String, layer: Layer) = Hit(zh, CurrencyFormat.apply(Units.apply(zh, en), currency), layer, upload, pii)

        glossary[zh]?.let { return finish(it, Layer.GLOSSARY) }
        dictionary[zh]?.let { return finish(it, Layer.DICTIONARY) }
        TemplateEngine.apply(zh)?.let { return finish(it, Layer.TEMPLATE) }
        cache[zh]?.let { return finish(it, Layer.CACHE) }
        return finish(zh, Layer.NONE)
    }
}

object AutoFit {
    fun textWidth(sample: String, px: Float): Float {
        var width = 0f
        for (ch in sample) width += if (ch.code > 255) px else px * 0.56f
        return width
    }

    fun fit(text: String, boxPx: Float, basePx: Float): Fit {
        val min = basePx * 0.7f
        if (textWidth(text, basePx) <= boxPx) return Fit(basePx, 1, false)
        if (textWidth(text, min) <= boxPx) return Fit(min, 1, false)
        if (textWidth(text, min) <= boxPx * 2f) return Fit(min, 2, false)
        return Fit(min, 2, true)
    }
}

object ExcludedScreens {
    private val screens = listOf(
        "orders" to listOf("我的订单", "订单编号", "待付款", "待发货", "查看物流"),
        "chat" to listOf("按住说话", "客服消息", "联系卖家", "旺旺留言"),
        "account" to listOf("账号与安全", "退出登录", "个人信息", "切换账号"),
        "payment" to listOf("支付密码", "收银台", "银行卡", "去支付"),
        "address" to listOf("收货地址", "详细地址", "收货人", "新增地址"),
    )

    fun detect(visible: List<String>): String? {
        val blob = visible.joinToString("\n")
        for ((id, words) in screens) {
            if (words.count { blob.contains(it) } >= 2) return id
        }
        return null
    }
}
