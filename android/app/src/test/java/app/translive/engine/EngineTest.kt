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
        assertEquals(11.2f, long.fontPx, 0.01f)
        assertTrue(long.lines == 2)
    }
}
