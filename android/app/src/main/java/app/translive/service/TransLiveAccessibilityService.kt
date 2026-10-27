package app.translive.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.translive.data.Dictionary
import app.translive.data.PhoneCache
import app.translive.data.Prefs
import app.translive.engine.ExcludedScreens
import app.translive.engine.Layer
import app.translive.engine.LookupChain
import app.translive.engine.TextClean
import app.translive.overlay.OverlayController
import app.translive.overlay.Patch
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.ArrayDeque

class TransLiveAccessibilityService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private lateinit var cache: PhoneCache
    private lateinit var overlay: OverlayController
    private val dict = HashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val pending = Runnable { capture() }
    private var lastKey = ""
    private var lastOcr = 0L
    private val eventTimes = ArrayDeque<Long>()
    private var latestBitmap: Bitmap? = null
    private var lastReported = ""
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build(),
        )
    }
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        cache = PhoneCache(this)
        dict.putAll(Dictionary.load(this))
        overlay = OverlayController(this)
        overlay.onPause = { prefs.setPaused(it); overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal()); schedule() }
        overlay.onOcr = { prefs.setOcr(it); overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal()) }
        overlay.onOriginal = { prefs.setShowOriginal(it); overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal()); schedule() }
        overlay.onReport = { if (lastReported.isNotEmpty()) prefs.report(lastReported) }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val now = android.os.SystemClock.uptimeMillis()
        eventTimes.addLast(now)
        while (eventTimes.isNotEmpty() && now - eventTimes.first() > 1000) eventTimes.removeFirst()
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, 80)
    }

    private fun capture() {
        if (!::overlay.isInitialized) return
        overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal())
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName || !prefs.isEnabled(pkg)) {
            overlay.hide()
            root.recycle()
            return
        }
        val nodes = ArrayList<Found>()
        walk(root, nodes, 0)
        root.recycle()
        val blocked = ExcludedScreens.detect(nodes.map { it.text }) != null
        val key = nodes.joinToString("|") { "${it.text}@${it.bounds.flattenToString()}" }
        if (key == lastKey) return
        lastKey = key
        val patches = ArrayList<Patch>()
        val misses = ArrayList<Found>()
        for (node in nodes) {
            val cached = cache.get(node.text)
            val usableCache = cached?.let { TextClean.present(it) }
            val hit = LookupChain.translate(
                node.text,
                prefs.glossary(),
                dict,
                if (usableCache != null) mapOf(node.text to usableCache) else emptyMap(),
                prefs.currency(),
                blocked || PiiScreen(node.text),
            )
            val en = if (hit.layer == Layer.NONE) null else TextClean.present(hit.en)
            if (en == null) misses.add(node)
            else {
                lastReported = node.text
                val (bg, fg) = colors(node.bounds)
                patches.add(Patch(en, node.bounds, bg, fg))
            }
        }
        overlay.showPatches(patches)
        if (misses.isNotEmpty() && !prefs.paused()) translateLive(misses.take(16), blocked)
        maybeOcr(nodes)
    }

    private fun translateLive(misses: List<Found>, blocked: Boolean) {
        if (batteryLow()) return
        for (node in misses) {
            translator.translate(node.text)
                .addOnSuccessListener { raw ->
                    val en = TextClean.present(raw) ?: return@addOnSuccessListener
                    cache.put(node.text, en)
                    if (!blocked && prefs.contribute() && !app.translive.engine.PiiFilter.hasPii(node.text)) {
                        // Shared library upload waits for a proxy URL. Nothing leaves the phone until then.
                    }
                    lastKey = ""
                    schedule()
                }
        }
    }

    private fun maybeOcr(nodes: List<Found>) {
        if (!prefs.ocr() || prefs.paused() || Build.VERSION.SDK_INT < 30) return
        if (eventTimes.size > 6) return
        if (prefs.dataSaver() && metered()) return
        if (batteryLow()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastOcr < 2500) return
        lastOcr = now
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                result.hardwareBuffer.close()
                if (hardware == null) return
                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                hardware.recycle()
                latestBitmap = bitmap
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image).addOnSuccessListener { vision ->
                    val extra = ArrayList<Patch>()
                    val ocrMisses = ArrayList<Found>()
                    for (block in vision.textBlocks) {
                        for (line in block.lines) {
                            val zh = line.text.trim()
                            val chinese = zh.count { it.code in 0x4E00..0x9FFF }
                            if (chinese < 2) continue
                            val box = line.boundingBox ?: continue
                            if (box.width() < 8 || box.height() < 8 || box.height() > 160) continue
                            if (nodes.any { Rect.intersects(it.bounds, box) }) continue
                            val cached = cache.get(zh)?.let { TextClean.present(it) }
                            val hit = LookupChain.translate(
                                zh,
                                prefs.glossary(),
                                dict,
                                if (cached != null) mapOf(zh to cached) else emptyMap(),
                                prefs.currency(),
                                false,
                            )
                            val en = if (hit.layer == Layer.NONE) null else TextClean.present(hit.en)
                            if (en == null) {
                                if (ocrMisses.size < 6) ocrMisses.add(Found(zh, Rect(box)))
                            } else {
                                val (bg, fg) = colors(box)
                                extra.add(Patch(en, Rect(box), bg, fg))
                            }
                        }
                    }
                    if (extra.isNotEmpty()) overlay.addPatches(extra)
                    if (ocrMisses.isNotEmpty()) translateLive(ocrMisses, false)
                }
            }

            override fun onFailure(errorCode: Int) = Unit
        })
    }

    private fun colors(bounds: Rect): Pair<Int, Int> {
        val bitmap = latestBitmap
        if (bitmap == null) return Color.WHITE to Color.parseColor("#14204A")
        val x = (bounds.left + 2).coerceIn(0, bitmap.width - 1)
        val y = (bounds.top + 2).coerceIn(0, bitmap.height - 1)
        val bg = bitmap.getPixel(x, y)
        val lum = (Color.red(bg) * 0.3 + Color.green(bg) * 0.59 + Color.blue(bg) * 0.11) / 255.0
        val fg = if (lum > 0.6) Color.parseColor("#14204A") else Color.WHITE
        return bg to fg
    }

    private fun metered(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    private fun batteryLow(): Boolean {
        if (!prefs.batterySaver()) return false
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return pct in 0..15
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<Found>, depth: Int): Boolean {
        if (node == null || depth > 40) return false
        var childText = false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (walk(child, out, depth + 1)) childText = true
            } finally {
                child.recycle()
            }
        }
        if (childText) return true
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val fromText = text.any { it.code in 0x4E00..0x9FFF }
        val raw = if (fromText) text else desc
        if (!raw.any { it.code in 0x4E00..0x9FFF }) return false
        if (!fromText && !descriptionIsLabel(node, desc)) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!boundsAreText(rect, raw)) return false
        out.add(Found(raw, rect))
        return true
    }

    private fun descriptionIsLabel(node: AccessibilityNodeInfo, desc: String): Boolean {
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("Image") || cls.contains("Texture") || cls.contains("Surface")) return false
        if (desc == "图片" || desc == "照片" || desc == "图像") return false
        return true
    }

    private fun boundsAreText(rect: Rect, raw: String): Boolean {
        if (rect.width() < 2 || rect.height() < 2) return false
        val dm = resources.displayMetrics
        if (rect.width() > dm.widthPixels * 0.94 && rect.height() > dm.heightPixels * 0.18) return false
        val line = 22f * dm.density
        val lines = (raw.length / 16).coerceIn(1, 4)
        if (rect.height() > line * (lines + 2)) return false
        return true
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hide()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (::overlay.isInitialized) overlay.hide()
        return super.onUnbind(intent)
    }

    private data class Found(val text: String, val bounds: Rect)
}

private fun PiiScreen(text: String) = app.translive.engine.PiiFilter.hasPii(text)
