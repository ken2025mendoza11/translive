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
            val hit = LookupChain.translate(
                node.text,
                prefs.glossary(),
                dict,
                if (cached != null) mapOf(node.text to cached) else emptyMap(),
                prefs.currency(),
                blocked || PiiScreen(node.text),
            )
            if (hit.layer == Layer.NONE) misses.add(node)
            else {
                lastReported = node.text
                val (bg, fg) = colors(node.bounds)
                patches.add(Patch(hit.en, node.bounds, bg, fg))
            }
        }
        overlay.showPatches(patches)
        if (misses.isNotEmpty() && !prefs.paused()) translateLive(misses, blocked)
        maybeOcr(nodes)
    }

    private fun translateLive(misses: List<Found>, blocked: Boolean) {
        if (batteryLow()) return
        for (node in misses) {
            translator.translate(node.text)
                .addOnSuccessListener { en ->
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
                    for (block in vision.textBlocks) {
                        val zh = block.text.trim()
                        if (!zh.any { it.code in 0x4E00..0x9FFF }) continue
                        val box = block.boundingBox ?: continue
                        if (nodes.any { Rect.intersects(it.bounds, box) }) continue
                        val hit = LookupChain.translate(zh, prefs.glossary(), dict, emptyMap(), prefs.currency(), false)
                        val en = if (hit.layer == Layer.NONE) zh else hit.en
                        extra.add(Patch(en, box, colors(box).first, colors(box).second))
                    }
                    if (extra.isNotEmpty()) overlay.addPatches(extra)
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

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<Found>, depth: Int) {
        if (node == null || depth > 40) return
        val raw = node.text?.toString()?.trim().orEmpty().ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
        if (raw.any { it.code in 0x4E00..0x9FFF }) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 1 && rect.height() > 1) out.add(Found(raw, rect))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walk(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
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
