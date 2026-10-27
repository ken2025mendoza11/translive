package app.translive.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.translive.data.Dictionary
import app.translive.data.PhoneCache
import app.translive.data.Prefs
import app.translive.engine.Chrome
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

class TransLiveAccessibilityService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private lateinit var cache: PhoneCache
    private lateinit var overlay: OverlayController
    private val dict = HashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val pending = Runnable { settle(fromScroll = scrolling) }
    private val watch = Runnable { watchForeground() }
    private var burstStart = 0L
    private var scrolling = false
    private var awaySince = 0L
    private var lastOcr = 0L
    private var shotBusy = false
    private var latestBitmap: Bitmap? = null
    private var shotFrame = Rect()
    private var lastReported = ""
    private val inFlight = HashSet<String>()
    private val sizes = HashMap<String, Float>()
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
        overlay.onPause = { prefs.setPaused(it); overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal()); overlay.attach() }
        overlay.onOcr = { prefs.setOcr(it); overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal()) }
        overlay.onOriginal = { prefs.setShowOriginal(it); overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal()); overlay.attach() }
        overlay.onReport = { if (lastReported.isNotEmpty()) prefs.report(lastReported) }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::overlay.isInitialized) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (pkg.contains("inputmethod") || pkg.endsWith(".ime")) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.post(watch)
        }
        if (pkg.isNotEmpty() && !prefs.isEnabled(pkg)) return
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) scrolling = true
        schedule()
    }

    private fun schedule() {
        val now = android.os.SystemClock.uptimeMillis()
        if (burstStart == 0L) burstStart = now
        handler.removeCallbacks(pending)
        if (now - burstStart >= 500) {
            burstStart = 0L
            handler.post(pending)
        } else {
            handler.postDelayed(pending, 200)
        }
    }

    private fun watchForeground() {
        if (!::overlay.isInitialized) return
        val top = topApp()
        val ours = top != null && prefs.isEnabled(top)
        val now = android.os.SystemClock.uptimeMillis()
        if (ours) {
            awaySince = 0L
            if (!overlay.attached()) overlay.attach()
        } else if (top != null) {
            if (awaySince == 0L) awaySince = now
            if (now - awaySince >= 400) overlay.hide()
        }
        if (overlay.attached()) handler.postDelayed(watch, 350)
    }

    private fun topApp(): String? {
        val list = windows ?: return rootInActiveWindow?.packageName?.toString()
        var pkg: String? = null
        for (window in list) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val name = root.packageName?.toString()
            root.recycle()
            if (name.isNullOrEmpty() || name == packageName) continue
            pkg = name
        }
        return pkg
    }

    private fun settle(fromScroll: Boolean) {
        burstStart = 0L
        val wasScrolling = fromScroll || scrolling
        scrolling = false
        if (!::overlay.isInitialized) return
        overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal())
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (!prefs.isEnabled(pkg)) {
            root.recycle()
            handler.post(watch)
            return
        }
        awaySince = 0L
        overlay.attach()
        handler.postDelayed(watch, 350)
        val found = ArrayList<Raw>()
        walk(root, found, 0)
        root.recycle()
        val blocked = ExcludedScreens.detect(found.map { it.zh }) != null
        val ready = ArrayList<Patch>()
        val misses = ArrayList<Raw>()
        var needShot = latestBitmap == null
        for (raw in found) {
            val cached = cache.get(raw.zh)?.let { TextClean.present(it) }
            val hit = LookupChain.translate(
                raw.zh,
                prefs.glossary(),
                dict,
                if (cached != null) mapOf(raw.zh to cached) else emptyMap(),
                "NONE",
                blocked || PiiScreen(raw.zh),
            )
            val en = if (hit.layer == Layer.NONE) null else TextClean.present(hit.en)
            val previous = overlay.peek(raw.key)
            if (en == null || Chrome.skipText(en)) {
                if (previous != null) ready.add(previous.copy(bounds = Rect(raw.bounds)))
                if (en == null) misses.add(raw)
                continue
            }
            lastReported = raw.zh
            val colors = colorsFor(raw, previous)
            if (colors == null) needShot = true
            ready.add(
                Patch(
                    raw.key,
                    en,
                    Rect(raw.bounds),
                    colors?.first ?: previous?.bg ?: Color.TRANSPARENT,
                    colors?.second ?: previous?.fg ?: Color.BLACK,
                    raw.textSizePx,
                    raw.bold,
                    raw.center,
                    Patch.NODE,
                ),
            )
        }
        overlay.upsert(Patch.NODE, ready, dropMissing = !wasScrolling)
        if (misses.isNotEmpty() && !prefs.paused()) translateLive(misses, blocked)
        if (needShot || (!wasScrolling && !prefs.paused() && prefs.ocr())) requestShot(runOcr = !wasScrolling && prefs.ocr() && !prefs.paused())
    }

    private fun translateLive(misses: List<Raw>, blocked: Boolean) {
        if (batteryLow()) return
        for (raw in misses) {
            if (!inFlight.add(raw.zh)) continue
            translator.translate(raw.zh).addOnSuccessListener { result ->
                val en = TextClean.present(result)
                if (en != null) cache.put(raw.zh, en)
                inFlight.remove(raw.zh)
                if (en == null || Chrome.skipText(raw.zh) || Chrome.skipText(en)) return@addOnSuccessListener
                val source = raw.key.substringBefore('|').toIntOrNull() ?: Patch.NODE
                if (!blocked && prefs.contribute() && !app.translive.engine.PiiFilter.hasPii(raw.zh)) {
                    // Shared upload waits for a proxy. Nothing leaves the phone until then.
                }
                val previous = overlay.peek(raw.key)
                val colors = colorsFor(raw, previous)
                overlay.put(
                    Patch(
                        raw.key,
                        en,
                        Rect(raw.bounds),
                        colors?.first ?: previous?.bg ?: Color.TRANSPARENT,
                        colors?.second ?: previous?.fg ?: Color.BLACK,
                        raw.textSizePx,
                        raw.bold,
                        raw.center,
                        source,
                    ),
                )
                if (colors == null) requestShot(runOcr = false)
            }.addOnFailureListener { inFlight.remove(raw.zh) }
        }
    }

    private fun requestShot(runOcr: Boolean) {
        if (shotBusy || Build.VERSION.SDK_INT < 30) return
        val now = android.os.SystemClock.uptimeMillis()
        if (runOcr && now - lastOcr < 1200) {
            if (latestBitmap != null) return
        }
        if (runOcr && (batteryLow() || (prefs.dataSaver() && metered()))) {
            if (latestBitmap != null) return
        }
        shotBusy = true
        if (runOcr) lastOcr = now
        val windowId = pddWindowId()
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                shotBusy = false
                val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                result.hardwareBuffer.close()
                if (hardware == null) return
                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                hardware.recycle()
                latestBitmap = bitmap
                if (runOcr && Build.VERSION.SDK_INT < 34) maskOwnPatches(bitmap)
                handler.post {
                    if (!::overlay.isInitialized) return@post
                    settle(fromScroll = false)
                    if (runOcr) readPhotoText(bitmap)
                }
            }

            override fun onFailure(errorCode: Int) {
                shotBusy = false
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && windowId > 0) {
            takeScreenshotOfWindow(windowId, mainExecutor, callback)
        } else {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        }
    }

    private fun pddWindowId(): Int {
        val list = windows ?: return -1
        var id = -1
        shotFrame = Rect()
        for (window in list) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val name = root.packageName?.toString()
            if (name != null && prefs.isEnabled(name)) {
                id = window.id
                window.getBoundsInScreen(shotFrame)
            }
            root.recycle()
        }
        return id
    }

    private fun maskOwnPatches(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val originX = if (Build.VERSION.SDK_INT >= 34) shotFrame.left else 0
        val originY = if (Build.VERSION.SDK_INT >= 34) shotFrame.top else 0
        // Full-screen shots include our overlay. Paint the stored background back over each label
        // before OCR so English is not read as Chinese.
        for (patch in overlay.drawn()) {
            paint.color = patch.bg
            canvas.drawRect(
                (patch.bounds.left - originX).toFloat(),
                (patch.bounds.top - originY).toFloat(),
                (patch.bounds.right - originX).toFloat(),
                (patch.bounds.bottom - originY).toFloat(),
                paint,
            )
        }
    }

    private fun readPhotoText(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image).addOnSuccessListener { vision ->
            val extra = ArrayList<Patch>()
            val misses = ArrayList<Raw>()
            for (block in vision.textBlocks) {
                for (line in block.lines) {
                    val zh = line.text.trim()
                    if (zh.count { it.code in 0x4E00..0x9FFF } < 2) continue
                    if (Chrome.skipText(zh)) continue
                    val box = line.boundingBox ?: continue
                    val screen = toScreen(box, bitmap)
                    if (screen.width() < 8 || screen.height() < 8 || screen.height() > 160) continue
                    if (inStatusBar(screen)) continue
                    val key = overlay.matchKey(Patch.OCR, zh, "", screen)
                    val cached = cache.get(zh)?.let { TextClean.present(it) }
                    val hit = LookupChain.translate(zh, prefs.glossary(), dict, if (cached != null) mapOf(zh to cached) else emptyMap(), "NONE", false)
                    val en = if (hit.layer == Layer.NONE) null else TextClean.present(hit.en)
                    val raw = Raw(key, zh, screen, "", screen.height() * 0.75f, false, false)
                    if (en == null || Chrome.skipText(en)) {
                        if (en == null) misses.add(raw)
                        continue
                    }
                    val colors = sample(screen) ?: continue
                    extra.add(Patch(key, en, screen, colors.first, colors.second, raw.textSizePx, false, screen.width() < screen.height() * 4, Patch.OCR))
                }
            }
            if (extra.isNotEmpty() || true) overlay.upsert(Patch.OCR, extra, dropMissing = true)
            if (misses.isNotEmpty()) translateLive(misses, false)
        }
    }

    private fun toScreen(box: Rect, bitmap: Bitmap): Rect {
        val dm = resources.displayMetrics
        val sx: Float
        val sy: Float
        val ox: Int
        val oy: Int
        if (Build.VERSION.SDK_INT >= 34 && shotFrame.width() > 0) {
            sx = shotFrame.width().toFloat() / bitmap.width.coerceAtLeast(1)
            sy = shotFrame.height().toFloat() / bitmap.height.coerceAtLeast(1)
            ox = shotFrame.left
            oy = shotFrame.top
        } else {
            sx = dm.widthPixels.toFloat() / bitmap.width.coerceAtLeast(1)
            sy = dm.heightPixels.toFloat() / bitmap.height.coerceAtLeast(1)
            ox = 0
            oy = 0
        }
        return Rect(
            ox + (box.left * sx).toInt(),
            oy + (box.top * sy).toInt(),
            ox + (box.right * sx).toInt(),
            oy + (box.bottom * sy).toInt(),
        )
    }

    private fun colorsFor(raw: Raw, previous: Patch?): Pair<Int, Int>? {
        if (previous != null && Color.alpha(previous.bg) != 0) {
            val dx = kotlin.math.abs(previous.bounds.left - raw.bounds.left)
            val dy = kotlin.math.abs(previous.bounds.top - raw.bounds.top)
            if (dx < 8f * resources.displayMetrics.density && dy < 8f * resources.displayMetrics.density) {
                return previous.bg to previous.fg
            }
        }
        return sample(raw.bounds)
    }

    private fun sample(bounds: Rect): Pair<Int, Int>? {
        val bitmap = latestBitmap ?: return null
        val originX = if (Build.VERSION.SDK_INT >= 34) shotFrame.left else 0
        val originY = if (Build.VERSION.SDK_INT >= 34) shotFrame.top else 0
        val w = bitmap.width
        val h = bitmap.height
        if (w < 2 || h < 2) return null
        fun px(x: Int, y: Int) = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
        val left = bounds.left - originX
        val right = bounds.right - originX
        val top = bounds.top - originY
        val bottom = bounds.bottom - originY
        val around = ArrayList<Int>(12)
        for (i in 0..5) {
            val x = left + (right - left) * i / 5
            around.add(px(x, top - 4))
            around.add(px(x, bottom + 4))
        }
        val bg = (around.groupingBy { it and 0x00F8F8F8.toInt() }.eachCount().maxBy { it.value }.key) or 0xFF000000.toInt()
        var fg = Color.parseColor("#222222")
        var best = 0
        for (i in 1..3) {
            val p = px(left + (right - left) * i / 4, top + (bottom - top) / 2)
            val dist = kotlin.math.abs(Color.red(p) - Color.red(bg)) +
                kotlin.math.abs(Color.green(p) - Color.green(bg)) +
                kotlin.math.abs(Color.blue(p) - Color.blue(bg))
            if (dist > best) {
                best = dist
                fg = p or 0xFF000000.toInt()
            }
        }
        if (best < 90) {
            val lum = (Color.red(bg) * 0.3 + Color.green(bg) * 0.59 + Color.blue(bg) * 0.11) / 255.0
            fg = if (lum > 0.62) Color.parseColor("#222222") else Color.WHITE
        }
        return bg to fg
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<Raw>, depth: Int): Int {
        if (node == null || depth > 40) return 0
        var state = 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val childState = walk(child, out, depth + 1)
                if (childState > state) state = childState
            } finally {
                child.recycle()
            }
        }
        if (state != 0) return state
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName
        if (Chrome.skipViewId(viewId)) return 2
        if (Chrome.skipText(text) || Chrome.skipText(desc)) return 2
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val dm = resources.displayMetrics
        val density = dm.density
        val className = node.className?.toString().orEmpty()
        val atEdge = rect.left < 72f * density || rect.right > dm.widthPixels - 72f * density
        val icon = Chrome.smallTitleIcon(node.isClickable, rect.width() / density, rect.height() / density, rect.top / density, className, atEdge)
        if ((text == "关闭" || text == "取消" || desc == "关闭" || desc == "取消") && icon) return 2
        if (icon && !text.any { it.code in 0x4E00..0x9FFF }) return 2
        val fromText = text.any { it.code in 0x4E00..0x9FFF }
        val raw = if (fromText) text else desc
        if (!raw.any { it.code in 0x4E00..0x9FFF }) return 0
        if (!fromText) {
            if (desc.length > 16) return 0
            if (className.contains("Image") || className.contains("Texture")) return 0
        }
        if (inStatusBar(rect) || !boundsOk(rect)) return 0
        val sizeKey = "$raw|${viewId.orEmpty()}"
        val size = sizes[sizeKey] ?: readTextSize(node, rect).also { sizes[sizeKey] = it }
        val sp = size / density
        val title = rect.top < statusBar() + 160f * density
        val bold = sp >= 16f && (title || rect.width() > 180f * density)
        val center = node.isClickable && rect.width() < 220f * density && rect.height() < 72f * density && raw.length <= 10
        val key = overlay.matchKey(Patch.NODE, raw, viewId.orEmpty(), rect)
        out.add(Raw(key, raw, Rect(rect), viewId.orEmpty(), size, bold, center))
        return 1
    }

    private fun readTextSize(node: AccessibilityNodeInfo, bounds: Rect): Float {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY, Bundle())
                val px = node.extraRenderingInfo?.textSizeInPx ?: 0f
                if (px > 0f) return px
            } catch (_: Exception) {
            }
        }
        return bounds.height() * 0.75f
    }

    private fun boundsOk(rect: Rect): Boolean {
        if (rect.width() < 2 || rect.height() < 2) return false
        val dm = resources.displayMetrics
        if (rect.height() > dm.heightPixels * 0.45 && rect.width() > dm.widthPixels * 0.9) return false
        if (rect.height() > 78f * dm.density) return false
        return true
    }

    private fun inStatusBar(rect: Rect) = rect.bottom <= statusBar() + 4

    private fun statusBar(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24f * resources.displayMetrics.density).toInt()
    }

    private fun metered(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    private fun batteryLow(): Boolean {
        if (!prefs.batterySaver()) return false
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..15
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hide()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (::overlay.isInitialized) overlay.hide()
        return super.onUnbind(intent)
    }

    private data class Raw(
        val key: String,
        val zh: String,
        val bounds: Rect,
        val viewId: String,
        val textSizePx: Float,
        val bold: Boolean,
        val center: Boolean,
    )
}

private fun PiiScreen(text: String) = app.translive.engine.PiiFilter.hasPii(text)
