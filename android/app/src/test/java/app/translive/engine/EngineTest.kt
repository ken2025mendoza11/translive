package app.translive.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateEngineTest {
    @Test fun slots() {
        assertEquals("3 left", TemplateEngine.apply("剩3张"))
        assertEquals("On ¥50+", TemplateEngine.apply("满50可用"))
        assertEquals("On ¥30+", TemplateEngine.apply("满30可减"))
        assertEquals("Spend ¥100, save ¥20", TemplateEngine.apply("满100减20"))
        assertEquals("¥5 off first item", TemplateEngine.apply("首件立减5元"))
        assertEquals("12K sold", TemplateEngine.apply("已拼1.2万件"))
        assertEquals("10K sold", TemplateEngine.apply("已拼1万件"))
        assertEquals("8600 sold", TemplateEngine.apply("已拼8600件"))
        assertEquals("50% off", TemplateEngine.apply("5折"))
        assertEquals("10% off", TemplateEngine.apply("9折"))
        assertEquals("From 50% off", TemplateEngine.apply("低至5折"))
        assertEquals("Next round 12:00", TemplateEngine.apply("下场12点开抢"))
        assertEquals("¥3.9", TemplateEngine.apply("¥3.9"))
        assertEquals("Subsidy ¥10.46", TemplateEngine.apply("补贴10.46元"))
        assertEquals("184K sold", TemplateEngine.apply("已售18.4万件"))
        assertEquals("¥30 off each ¥200", TemplateEngine.apply("每满200减30"))
        assertEquals("Add", UiCopy.phrase("立即加购"))
        assertTrue(TemplateEngine.apply("券后¥15.54")!!.startsWith("After coupon"))
    }
}

class PiiFilterTest {
    @Test fun dropsPersonalStrings() {
        assertTrue(PiiFilter.hasPii("手机 13812345678"))
        assertTrue(PiiFilter.hasPii("write me at ada@example.com"))
        assertTrue(PiiFilter.hasPii("订单 2026102700123456"))
        assertTrue(PiiFilter.hasPii("SF1234567890123CN"))
        assertFalse(PiiFilter.hasPii("立即领取"))
        assertFalse(PiiFilter.hasPii("¥3.9"))
    }
}

class LookupChainTest {
    private val dict = mapOf("立即领取" to "Claim now", "规则" to "Rules", "先用后付" to "Buy now, pay later")

    @Test fun stopsAtFirstHit() {
        val gloss = LookupChain.translate("规则", mapOf("规则" to "The rules"), dict, emptyMap(), "NONE", false)
        assertEquals(Layer.GLOSSARY, gloss.layer)
        assertEquals("The rules", gloss.en)

        val known = LookupChain.translate("立即领取", emptyMap(), dict, emptyMap(), "NONE", false)
        assertEquals(Layer.DICTIONARY, known.layer)
        assertEquals("Claim now", known.en)

        val templated = LookupChain.translate("已拼1.2万件", emptyMap(), dict, emptyMap(), "PHP", false)
        assertEquals(Layer.TEMPLATE, templated.layer)
        assertEquals("12K sold", templated.en)

        val price = LookupChain.translate("¥3.9", emptyMap(), dict, emptyMap(), "PHP", false)
        assertEquals("¥3.9 (≈₱31)", price.en)

        val cached = LookupChain.translate("新品词", emptyMap(), dict, mapOf("新品词" to "Fresh phrase"), "NONE", false)
        assertEquals(Layer.CACHE, cached.layer)

        val blocked = LookupChain.translate("订单 13812345678", emptyMap(), dict, emptyMap(), "NONE", true)
        assertFalse(blocked.upload)
        assertTrue(blocked.pii)

        val stitched = LookupChain.translate("假一赔十，极速退款", emptyMap(), mapOf("极速退款" to "Instant refund"), emptyMap(), "NONE", false)
        assertEquals(Layer.DICTIONARY, stitched.layer)
        assertFalse(stitched.en.any { it.code in 0x4E00..0x9FFF })
        assertTrue(stitched.en.contains("10× if fake"))
        assertTrue(stitched.en.contains("Instant refund"))

        val partial = LookupChain.translate("强生正品", emptyMap(), emptyMap(), emptyMap(), "NONE", false)
        assertEquals(Layer.NONE, partial.layer)
    }
}

class TextCleanTest {
    @Test fun dropsLeftoverChineseAndRepeatedWords() {
        assertEquals(null, TextClean.present("# 店 店 1nd"))
        assertEquals("Baby Milk Soap", TextClean.present("Baby Milk Milk Soap"))
        assertEquals("Back", TextClean.present("Back"))
    }
}

class AutoFitTest {
    @Test fun shrinksThenWraps() {
        val fits = AutoFit.fit("Rules", 200f, 16f)
        assertEquals(1, fits.lines)
        assertEquals(16f, fits.fontPx)

        val long = AutoFit.fit("Children's Day gift set with extra wrapping", 80f, 16f)
        assertEquals(10f, long.fontPx, 0.01f)
        assertEquals(3, long.lines)
        assertTrue(long.ellipsis)
    }
}

class ChromeTest {
    @Test fun leavesBackAndShareUntouched() {
        assertTrue(Chrome.skipText("分享"))
        assertTrue(Chrome.skipText("分享按钮"))
        assertTrue(Chrome.skipText("分享给好友"))
        assertTrue(Chrome.skipText("去分享"))
        assertTrue(Chrome.skipText("< 返回"))
        assertTrue(Chrome.skipText("返回按钮"))
        assertTrue(Chrome.skipText("Share"))
        assertTrue(Chrome.skipText("Navigate up"))
        assertFalse(Chrome.skipText("返回顶部"))
        assertFalse(Chrome.skipText("商品分享赚钱活动"))
        assertFalse(Chrome.skipText("搜索"))
        assertTrue(Chrome.skipViewId("com.xunmeng.pinduoduo:id/iv_share"))
        assertTrue(Chrome.skipViewId("com.xunmeng.pinduoduo:id/title_bar_back"))
        assertTrue(Chrome.skipViewId("com.xunmeng.pinduoduo:id/btn_share"))
        assertFalse(Chrome.skipViewId("com.xunmeng.pinduoduo:id/title"))
        assertTrue(Chrome.smallTitleIcon(true, 40f, 40f, 24f, "android.widget.ImageButton", true))
        assertFalse(Chrome.smallTitleIcon(true, 40f, 40f, 24f, "android.widget.ImageButton", false))
        assertFalse(Chrome.smallTitleIcon(true, 200f, 48f, 80f, "android.widget.TextView", true))
        assertTrue(Chrome.skipEnglish("Back"))
        assertTrue(Chrome.skipEnglish("Share"))
        assertTrue(Chrome.skipEnglish("Navigate up"))
        assertFalse(Chrome.skipEnglish("Back in stock"))
        assertFalse(Chrome.skipEnglish("Share to get ¥5"))
    }
}

class KeyMatchTest {
    @Test fun identicalLabelsKeepDistinctKeys() {
        val existing = listOf(
            KeyMatch.Item("0|立即领取||1", "立即领取", "", 100, 10, 40),
            KeyMatch.Item("0|立即领取||2", "立即领取", "", 200, 10, 40),
            KeyMatch.Item("0|立即领取||3", "立即领取", "", 300, 10, 40),
        )
        val nodes = listOf(
            KeyMatch.Node(0, "立即领取", "", 180, 10, 40),
            KeyMatch.Node(1, "立即领取", "", 280, 10, 40),
            KeyMatch.Node(2, "立即领取", "", 380, 10, 40),
        )
        val assigned = KeyMatch.assign(nodes, existing, 80)
        assertEquals(3, assigned.values.toSet().size)
        assertEquals("0|立即领取||1", assigned[0])
        assertEquals("0|立即领取||2", assigned[1])
        assertEquals("0|立即领取||3", assigned[2])
    }
}

class ShotPolicyTest {
    @Test fun colorAndOcrGates() {
        assertFalse(ShotPolicy.colorShot(1000, false, true, 0, 0, 0, scrolling = true, fromShot = false))
        assertFalse(ShotPolicy.colorShot(1000, false, true, 0, 0, 0, scrolling = false, fromShot = true))
        assertFalse(ShotPolicy.colorShot(1000, false, true, 0, 0, 500, scrolling = false, fromShot = false))
        assertTrue(ShotPolicy.colorShot(2000, false, false, 0, 0, 0, scrolling = false, fromShot = false))
        assertTrue(ShotPolicy.colorShot(2000, true, true, 0, 100, 0, scrolling = false, fromShot = false))
        assertTrue(ShotPolicy.colorShot(2000, true, false, 150, 100, 0, scrolling = false, fromShot = false))
        assertFalse(ShotPolicy.colorShot(2000, true, false, 100, 150, 0, scrolling = false, fromShot = false))

        assertFalse(ShotPolicy.ocrShot(1000, 0, scrolling = false, paused = false, ocrOn = true, fromShot = false))
        assertFalse(ShotPolicy.ocrShot(3000, 0, scrolling = false, paused = false, ocrOn = true, fromShot = true))
        assertFalse(ShotPolicy.ocrShot(3000, 0, scrolling = true, paused = false, ocrOn = true, fromShot = false))
        assertFalse(ShotPolicy.ocrShot(3000, 0, scrolling = false, paused = true, ocrOn = true, fromShot = false))
        assertFalse(ShotPolicy.ocrShot(3000, 0, scrolling = false, paused = false, ocrOn = false, fromShot = false))
        assertTrue(ShotPolicy.ocrShot(3000, 0, scrolling = false, paused = false, ocrOn = true, fromShot = false))
    }
}

class ForegroundPickTest {
    private val enabled = { pkg: String -> pkg == "com.xunmeng.pinduoduo" }
    private val pdd = ForegroundPick.Win(1, 1, "com.xunmeng.pinduoduo", 0, 0, 1000, 2000)

    @Test fun picksTheShoppingAppAndOccluders() {
        val only = ForegroundPick.pick(listOf(pdd), "app.translive", enabled, 1000, 2000)
        assertEquals(1, only!!.windowId)
        assertTrue(only.occluders.isEmpty())

        val overlay = ForegroundPick.Win(9, 4, "app.translive", 900, 200, 1000, 500)
        val own = ForegroundPick.Win(8, 1, "app.translive", 0, 0, 1000, 2000)
        val ignored = ForegroundPick.pick(listOf(overlay, own, pdd), "app.translive", enabled, 1000, 2000)
        assertEquals("com.xunmeng.pinduoduo", ignored!!.pkg)
        assertTrue(ignored.occluders.isEmpty())

        val small = ForegroundPick.Win(2, 1, "com.vivo.floating", 100, 100, 700, 1100)
        val withSmall = ForegroundPick.pick(listOf(small, pdd), "app.translive", enabled, 1000, 2000)
        assertEquals(1, withSmall!!.windowId)
        assertEquals(1, withSmall.occluders.size)

        val other = ForegroundPick.Win(3, 1, "com.android.launcher", 0, 0, 1000, 2000)
        assertEquals(null, ForegroundPick.pick(listOf(other, pdd), "app.translive", enabled, 1000, 2000))
        assertEquals(null, ForegroundPick.pick(listOf(other), "app.translive", enabled, 1000, 2000))

        val partial = ForegroundPick.Win(4, 1, "com.xunmeng.pinduoduo", 0, 0, 800, 1000)
        assertEquals(null, ForegroundPick.pick(listOf(partial), "app.translive", enabled, 1000, 2000))

        assertTrue(ForegroundPick.occluded(0, 0, 10, 10, listOf(intArrayOf(5, 5, 15, 15))))
        assertFalse(ForegroundPick.occluded(0, 0, 10, 10, listOf(intArrayOf(10, 0, 20, 10))))
        assertFalse(ForegroundPick.occluded(0, 0, 10, 10, emptyList()))
    }
}

class BoldMeterTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun bars(w: Int, h: Int, bar: Int): IntArray {
        val pixels = IntArray(w * h) { white }
        for (y in 0 until h) {
            var x = 0
            while (x + bar <= w) {
                for (i in 0 until bar) pixels[y * w + x + i] = black
                x += bar + bar
            }
        }
        return pixels
    }

    @Test fun strokeWidthNotCoverage() {
        assertEquals(false, BoldMeter.isBold(bars(40, 40, 3), 40, 40, white))
        assertEquals(true, BoldMeter.isBold(bars(40, 40, 6), 40, 40, white))

        val tight = bars(20, 40, 6)
        val wide = IntArray(300 * 48) { white }
        for (y in 0 until 40) {
            for (x in 0 until 20) wide[(y + 4) * 300 + (x + 40)] = tight[y * 20 + x]
        }
        assertEquals(BoldMeter.isBold(tight, 20, 40, white), BoldMeter.isBold(wide, 300, 48, white))

        val few = IntArray(40 * 40) { white }
        for (i in 0 until 5) few[i] = black
        assertEquals(null, BoldMeter.isBold(few, 40, 40, white))
        assertEquals(null, BoldMeter.isBold(IntArray(40 * 40) { white }, 40, 40, white))
    }
}
