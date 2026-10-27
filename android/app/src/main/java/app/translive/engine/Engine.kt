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

    private val fragments: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("已售\\s*(\\d+(?:\\.\\d+)?)\\s*万\\+?\\s*件?") to { m -> "${wanToK(m.groupValues[1])} sold" },
        Regex("已抢\\s*(\\d+(?:\\.\\d+)?)\\s*万\\+?\\s*件?") to { m -> "${wanToK(m.groupValues[1])} sold" },
        Regex("已拼\\s*(\\d+(?:\\.\\d+)?)\\s*万\\+?\\s*件?") to { m -> "${wanToK(m.groupValues[1])} sold" },
        Regex("补贴\\s*(\\d+(?:\\.\\d+)?)\\s*元") to { m -> "Subsidy ¥${m.groupValues[1]}" },
        Regex("每满\\s*(\\d+(?:\\.\\d+)?)\\s*减\\s*(\\d+(?:\\.\\d+)?)") to { m -> "¥${m.groupValues[2]} off each ¥${m.groupValues[1]}" },
        Regex("满\\s*(\\d+(?:\\.\\d+)?)\\s*减(?!\\d)") to { m -> "Over ¥${m.groupValues[1]}" },
        Regex("[¥￥]\\s*(\\d+(?:\\.\\d+)?)\\s*起") to { m -> "From ¥${m.groupValues[1]}" },
        Regex("(\\d+(?:\\.\\d+)?)\\s*起") to { m -> "From ¥${m.groupValues[1]}" },
        Regex("券后价?") to { _ -> "After coupon " },
        Regex("官方正品") to { _ -> "Official" },
        Regex("正品险") to { _ -> "Genuine" },
        Regex("万人团") to { _ -> "Mega group" },
        Regex("即将开抢") to { _ -> "Soon" },
        Regex("(\\d+(?:\\.\\d+)?)\\s*元") to { m -> "¥${m.groupValues[1]}" },
        Regex("减\\s*(\\d+(?:\\.\\d+)?)") to { m -> "¥${m.groupValues[1]} off" },
    )

    fun apply(zh: String): String? {
        for ((regex, run) in templates) {
            val match = regex.matchEntire(zh) ?: continue
            return run(match)
        }
        return rewrite(zh)
    }

    /** Shopping bits inside a longer label. Null when Chinese is still left. */
    fun rewrite(zh: String): String? {
        val out = soften(zh)
        if (out == zh.trim()) return null
        if (out.any { it.code in 0x4E00..0x9FFF }) return null
        if (!out.any { it.isLetter() }) return null
        return out
    }

    fun soften(zh: String): String {
        var out = zh.trim()
        for ((regex, run) in fragments) out = regex.replace(out) { run(it) }
        return out.replace(Regex("\\s+"), " ").trim()
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
        if (Chrome.skipText(zh)) return finish(zh, Layer.NONE)
        UiCopy.phrase(zh)?.let { return finish(it, Layer.DICTIONARY) }
        dictionary[zh]?.let { return finish(it, Layer.DICTIONARY) }
        TemplateEngine.apply(zh)?.let { return finish(it, Layer.TEMPLATE) }
        stitch(zh, dictionary)?.let { return finish(it, Layer.DICTIONARY) }
        cache[zh]?.let { return finish(it, Layer.CACHE) }
        return finish(zh, Layer.NONE)
    }

    private val chrome = mapOf(
        "拍照" to "Camera",
        "拍图" to "Camera",
        "拍视频" to "Video",
        "第一名" to "1st place",
        "第1名" to "1st place",
        "第二名" to "2nd place",
        "第三名" to "3rd place",
        "正品保证" to "Genuine",
        "正品保障" to "Genuine",
        "假一赔十" to "10× if fake",
    )

    private val rank = Regex("第(\\d+)名")

    /** Replace known phrases inside a longer label. Null when Chinese is still left. */
    fun stitch(zh: String, dictionary: Map<String, String>): String? {
        var out = TemplateEngine.soften(zh)
        out = rank.replace(out) { m ->
            when (val n = m.groupValues[1].toIntOrNull()) {
                null -> m.value
                1 -> "1st place"
                2 -> "2nd place"
                3 -> "3rd place"
                else -> "${n}th place"
            }
        }
        val book = HashMap<String, String>(dictionary.size + chrome.size)
        book.putAll(chrome)
        book.putAll(dictionary)
        val keys = book.keys.filter { it.length >= 2 && out.contains(it) }.sortedByDescending { it.length }
        for (key in keys) {
            val en = book[key] ?: continue
            out = out.replace(key, en)
        }
        out = out.replace("，", ", ").replace("、", ", ").replace(Regex("\\s+"), " ").trim()
        if (out == zh.trim()) return null
        if (out.any { it.code in 0x4E00..0x9FFF }) return null
        if (!out.any { it.isLetter() }) return null
        return out
    }
}

object UiCopy {
    private val exact = mapOf(
        "立即加购" to "Add",
        "加入购物车" to "Add to cart",
        "加购" to "Add",
        "品牌秒杀" to "Brand flash sale",
        "限时秒杀" to "Flash sale",
        "整点抢券" to "Timed coupons",
        "点我夺宝" to "Prize draw",
        "砸金蛋" to "Golden egg",
        "三单挑战" to "3-order challenge",
        "跨店满减" to "Multi-store",
        "百亿补贴" to "Subsidized",
        "万人团" to "Group buy",
        "券后" to "After coupon",
        "券后价" to "After coupon",
        "去结算" to "Checkout",
        "官方正品" to "Official",
        "正品险" to "Genuine",
        "全场每满200减30" to "¥30 off every ¥200",
        "查看物流" to "Track",
        "确认收货" to "Received",
        "退款" to "Refund",
    )

    fun phrase(zh: String): String? = exact[zh.trim()]

    fun polish(en: String): String {
        var s = en
        s = s.replace(Regex("(?i)have (a |to )?grab"), "Sold")
        s = s.replace(Regex("(?i)\\bsubsidize\\b"), "Subsidy")
        s = s.replace(Regex("(?i)brand\\s*after coupon"), "After coupon")
        s = s.replace(Regex("(?i)^product name:\\s*"), "")
        s = s.replace(Regex("(?i)\\s*yuan\\b"), "")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}

object Chrome {
    private val arrows = charArrayOf('<', '‹', '←', '>', '〈', '〉', '«', '»')

    fun normalized(raw: String): String {
        var t = raw.trim()
        while (t.isNotEmpty() && (t[0] in arrows || t[0].isWhitespace())) t = t.substring(1).trimStart()
        while (t.isNotEmpty() && (t.last() in arrows || t.last().isWhitespace())) t = t.dropLast(1).trimEnd()
        return t
    }

    /** Back and Share chrome. "返回顶部" and long labels that only contain 分享 stay. */
    fun skipText(raw: String): Boolean {
        val t = normalized(raw)
        if (t.isEmpty()) return false
        if (t == "返回顶部" || t.startsWith("返回顶部")) return false
        val lower = t.lowercase()
        if (lower == "back to top" || lower.startsWith("back to top")) return false
        if (t == "去分享") return true
        if (t.startsWith("返回") || t.startsWith("后退") || t.startsWith("分享")) return true
        if (lower == "back" || lower.startsWith("back ") || lower == "share" || lower.startsWith("share ")) return true
        if (lower.startsWith("navigate up")) return true
        return false
    }

    /** English output only. "Back in stock" and "Share to get" stay. */
    fun skipEnglish(en: String): Boolean {
        val t = normalized(en).lowercase()
        return t == "back" || t == "share" || t == "navigate up"
    }

    fun skipViewId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val leaf = id.substringAfterLast('/').lowercase()
        val parts = leaf.split('_', '-', '.')
        if (parts.any { it == "back" || it == "share" }) return true
        return listOf(
            "nav_up", "navigate_up", "ic_back", "iv_back", "iv_share",
            "btn_share", "title_bar_back", "share_btn",
        ).any { leaf.contains(it) }
    }

    fun smallTitleIcon(
        clickable: Boolean,
        widthDp: Float,
        heightDp: Float,
        topDp: Float,
        className: String,
        atEdge: Boolean,
    ): Boolean {
        if (!clickable || !atEdge) return false
        if (widthDp > 56f || heightDp > 56f || topDp > 120f) return false
        val cls = className.lowercase()
        return cls.contains("image") || cls.contains("button") || cls.endsWith(".view") || cls == "android.view.view"
    }
}

object KeyMatch {
    data class Node(val id: Int, val text: String, val viewId: String, val top: Int, val left: Int, val height: Int)
    data class Item(val key: String, val text: String, val viewId: String, val top: Int, val left: Int, val height: Int)

    /** Each existing key is given to at most one node. Nearest pair wins. */
    fun assign(nodes: List<Node>, existing: List<Item>, scrollDy: Int): Map<Int, String> {
        val pairs = ArrayList<Triple<Long, Int, String>>()
        for (node in nodes) {
            for (item in existing) {
                if (item.text != node.text || item.viewId != node.viewId) continue
                val dx = item.left - node.left
                val dy = item.top + scrollDy - node.top
                val dist = dx.toLong() * dx + dy.toLong() * dy
                val radius = (1.5f * maxOf(node.height, item.height)).toInt().coerceAtLeast(8) + kotlin.math.abs(scrollDy)
                if (dist <= radius.toLong() * radius) pairs.add(Triple(dist, node.id, item.key))
            }
        }
        pairs.sortBy { it.first }
        val usedNodes = HashSet<Int>()
        val usedKeys = HashSet<String>()
        val out = HashMap<Int, String>()
        for ((_, id, key) in pairs) {
            if (id in usedNodes || key in usedKeys) continue
            usedNodes.add(id)
            usedKeys.add(key)
            out[id] = key
        }
        return out
    }
}

object ShotPolicy {
    const val COLOR_GAP_MS = 700L // min gap between colour-only shots
    const val OCR_GAP_MS = 1200L // min gap between OCR shots

    fun colorShot(
        now: Long,
        hasBitmap: Boolean,
        missingColors: Boolean,
        lastScroll: Long,
        shotAt: Long,
        lastColorShot: Long,
        scrolling: Boolean,
        fromShot: Boolean,
    ): Boolean {
        if (scrolling || fromShot) return false
        if (now - lastColorShot < COLOR_GAP_MS) return false
        return !hasBitmap || missingColors || lastScroll > shotAt
    }

    fun ocrShot(
        now: Long,
        lastOcr: Long,
        scrolling: Boolean,
        paused: Boolean,
        ocrOn: Boolean,
        fromShot: Boolean,
    ): Boolean = !fromShot && !scrolling && !paused && ocrOn && now - lastOcr >= OCR_GAP_MS
}

object ForegroundPick {
    const val TYPE_APPLICATION = 1 // == AccessibilityWindowInfo.TYPE_APPLICATION
    const val MIN_APP_AREA = 0.5f // enabled window must cover >= 50% of screen
    const val FULL_COVER = 0.9f // an app window above covering >= 90% means ours is hidden

    data class Win(val id: Int, val type: Int, val pkg: String?, val l: Int, val t: Int, val r: Int, val b: Int)
    data class Pick(
        val windowId: Int,
        val pkg: String,
        val l: Int,
        val t: Int,
        val r: Int,
        val b: Int,
        val occluders: List<IntArray>,
    )

    fun pick(wins: List<Win>, ownPkg: String, enabled: (String) -> Boolean, screenW: Int, screenH: Int): Pick? {
        val screen = screenW.toLong() * screenH
        val occ = ArrayList<IntArray>()
        for (w in wins) {
            if (w.type != TYPE_APPLICATION) continue
            val pkg = w.pkg ?: continue
            if (pkg == ownPkg) continue
            val area = (w.r - w.l).toLong().coerceAtLeast(0) * (w.b - w.t).toLong().coerceAtLeast(0)
            if (enabled(pkg) && area >= screen * MIN_APP_AREA) return Pick(w.id, pkg, w.l, w.t, w.r, w.b, occ.toList())
            if (area >= screen * FULL_COVER) return null
            occ.add(intArrayOf(w.l, w.t, w.r, w.b))
        }
        return null
    }

    fun occluded(l: Int, t: Int, r: Int, b: Int, occluders: List<IntArray>): Boolean =
        occluders.any { o -> l < o[2] && o[0] < r && t < o[3] && o[1] < b }
}

object BoldMeter {
    const val INK_DIST = 80 // |dR|+|dG|+|dB| from bg counts as ink
    const val MIN_INK_PX = 20 // fewer ink pixels -> unknown
    const val MIN_GLYPH_H = 8 // tight ink box shorter than this -> unknown
    const val BOLD_STROKE_RATIO = 0.11f // median stroke / glyph height

    fun isBold(pixels: IntArray, w: Int, h: Int, bg: Int): Boolean? {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return null
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var inkCount = 0
        val ink = BooleanArray(w * h)
        for (y in 0 until h) {
            var row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                val dist = kotlin.math.abs(((p shr 16) and 0xFF) - bgR) +
                    kotlin.math.abs(((p shr 8) and 0xFF) - bgG) +
                    kotlin.math.abs((p and 0xFF) - bgB)
                if (dist > INK_DIST) {
                    ink[row + x] = true
                    inkCount++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (inkCount < MIN_INK_PX || maxY < minY) return null
        val boxH = maxY - minY + 1
        if (boxH < MIN_GLYPH_H) return null
        val runs = ArrayList<Int>()
        for (y in minY..maxY) {
            var run = 0
            val row = y * w
            for (x in minX..maxX) {
                if (ink[row + x]) run++
                else if (run > 0) {
                    runs.add(run)
                    run = 0
                }
            }
            if (run > 0) runs.add(run)
        }
        if (runs.isEmpty()) return null
        runs.sort()
        val mid = runs.size / 2
        val median = if (runs.size % 2 == 1) runs[mid].toFloat() else (runs[mid - 1] + runs[mid]) / 2f
        return median / boxH >= BOLD_STROKE_RATIO
    }
}

object TextClean {
    fun present(en: String): String? {
        val cjk = en.count { it.code in 0x4E00..0x9FFF }
        val latin = en.count { (it in 'A'..'Z') || (it in 'a'..'z') }
        val source = when {
            cjk == 0 -> en
            latin >= 8 && cjk * 4 <= latin -> en.filter { it.code !in 0x4E00..0x9FFF }
            else -> return null
        }
        val words = source.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val kept = ArrayList<String>(words.size)
        for (word in words) {
            if (kept.isEmpty() || !kept.last().equals(word, ignoreCase = true)) kept.add(word)
        }
        val tidy = UiCopy.polish(kept.joinToString(" "))
        if (!tidy.any { it.isLetter() }) return null
        return tidy
    }
}

object AutoFit {
    fun textWidth(sample: String, px: Float): Float {
        var width = 0f
        for (ch in sample) width += if (ch.code > 255) px else px * 0.56f
        return width
    }

    fun fit(text: String, boxPx: Float, basePx: Float): Fit {
        val min = (basePx * 0.45f).coerceAtLeast(10f)
        if (textWidth(text, basePx) <= boxPx) return Fit(basePx, 1, false)
        if (textWidth(text, min) <= boxPx) return Fit(min, 1, false)
        if (textWidth(text, min) <= boxPx * 2f) return Fit(min, 2, false)
        if (textWidth(text, min) <= boxPx * 3f) return Fit(min, 3, false)
        return Fit(min, 3, true)
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
