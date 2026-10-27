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
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.translive.data.Dictionary
import app.translive.data.PhoneCache
import app.translive.data.Prefs
import app.translive.engine.BoldMeter
import app.translive.engine.Chrome
import app.translive.engine.ExcludedScreens
import app.translive.engine.ForegroundPick
import app.translive.engine.KeyMatch
import app.translive.engine.Layer
import app.translive.engine.LookupChain
import app.translive.engine.ShotPolicy
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
    private var foreground = false
    private var lastScroll = 0L
    private var shotAt = 0L
    private var lastColorShot = 0L
    private var retryPending = false
    private var suppressRetry = false
    private var pendingScrollDy = 0
    private var nextSlot = 1
    private var lastOcr = 0L
    private var shotBusy = false
    private var latestBitmap: Bitmap? = null
    private var shotFrame = Rect()
    private var shotWin = -1
    private var lastReported = ""
    private val inFlight = HashSet<String>()
    private val sizes = object : LinkedHashMap<String, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) = size > 500
    }
    private val weight = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }
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
        overlay.onPause = { prefs.setPaused(it); overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal()) }
        overlay.onOcr = { prefs.setOcr(it); overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal()) }
        overlay.onOriginal = { prefs.setShowOriginal(it); overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal()) }
        overlay.onReport = { if (lastReported.isNotEmpty()) prefs.report(lastReported) }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::overlay.isInitialized) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (pkg.contains("inputmethod") || pkg.endsWith(".ime")) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            sizes.clear()
            weight.clear()
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            postWatch(0)
        }
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastScroll = android.os.SystemClock.uptimeMillis()
        }
        if (pkg.isNotEmpty() && !prefs.isEnabled(pkg)) return
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrolling = true
            nudge(event)
        }
        schedule()
    }

    private fun nudge(event: AccessibilityEvent) {
        val container = Rect()
        val source = event.source
        source?.getBoundsInScreen(container)
        source?.recycle()
        val dx = if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaX else 0
        val dy = if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else 0
        if (dx != 0 || dy != 0) {
            overlay.shift(container, -dx, -dy)
        } else {
            pendingScrollDy = 0
            overlay.concealInside(container)
        }
    }

    private fun postWatch(delay: Long) {
        handler.removeCallbacks(watch)
        handler.postDelayed(watch, delay)
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
        if (windows == null) {
            postWatch(350)
            return
        }
        val pick = currentPick()
        val ours = pick != null
        val now = android.os.SystemClock.uptimeMillis()
        if (ours) {
            awaySince = 0L
            foreground = true
            overlay.setLive(true)
            overlay.setOccluders(pick.occluders)
            if (!overlay.attached()) overlay.attach()
            postWatch(350)
        } else {
            foreground = false
            overlay.setLive(false)
            if (awaySince == 0L) awaySince = now
            if (now - awaySince >= 400) {
                overlay.hide()
                handler.removeCallbacks(watch)
            } else {
                postWatch(350)
            }
        }
    }

    private fun currentPick(): ForegroundPick.Pick? {
        val list = windows ?: return null
        val wins = ArrayList<ForegroundPick.Win>(list.size)
        for (window in list) {
            val root = window.root
            val pkg = root?.packageName?.toString()
            root?.recycle()
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            wins.add(ForegroundPick.Win(window.id, window.type, pkg, bounds.left, bounds.top, bounds.right, bounds.bottom))
        }
        val dm = resources.displayMetrics
        return ForegroundPick.pick(wins, packageName, prefs::isEnabled, dm.widthPixels, dm.heightPixels)
    }

    private fun windowRoot(id: Int): AccessibilityNodeInfo? {
        val list = windows ?: return null
        for (window in list) {
            if (window.id == id) return window.root
        }
        return null
    }

    private fun settle(fromScroll: Boolean, fromShot: Boolean = false) {
        burstStart = 0L
        val wasScrolling = fromScroll || scrolling
        scrolling = false
        if (!::overlay.isInitialized) return
        overlay.sync(prefs.paused(), prefs.ocr(), prefs.showOriginal())
        val pick = currentPick()
        if (pick == null) {
            postWatch(0)
            return
        }
        val dm = resources.displayMetrics
        val walked = ArrayList<Raw>()
        val above = ArrayList<IntArray>()
        var anyRoot = false
        for (win in pick.appWindows) {
            val root = windowRoot(win.id) ?: continue
            anyRoot = true
            val mine = ArrayList<Raw>()
            walk(root, mine, 0)
            root.recycle()
            for (raw in mine) {
                val b = raw.bounds
                if (!ForegroundPick.occluded(b.left, b.top, b.right, b.bottom, above)) walked.add(raw.copy(win = win.id))
            }
            val textRects = mine.map { intArrayOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) }
            ForegroundPick.coverRect(win, textRects, dm.widthPixels, dm.heightPixels)?.let { above.add(it) }
        }
        if (!anyRoot) {
            postWatch(0)
            return
        }
        awaySince = 0L
        foreground = true
        overlay.setLive(true)
        overlay.setOccluders(pick.occluders)
        if (!overlay.attached()) {
            overlay.attach()
            postWatch(350)
        }
        val found = bindKeys(walked, Patch.NODE)
        val blocked = ExcludedScreens.detect(found.map { it.zh }) != null
        val ready = ArrayList<Patch>()
        val misses = ArrayList<Raw>()
        var wantWin = -1
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
            if (en == null || Chrome.skipEnglish(en)) {
                if (previous != null) ready.add(previous.copy(bounds = Rect(raw.bounds)))
                if (en == null) misses.add(raw)
                continue
            }
            lastReported = raw.zh
            val colors = colorsFor(raw.bounds, previous, raw.win)
            val patch = Patch(
                raw.key,
                en,
                Rect(raw.bounds),
                colors?.first ?: previous?.bg ?: Color.TRANSPARENT,
                colors?.second ?: previous?.fg ?: Color.BLACK,
                raw.textSizePx,
                boldOf(raw.key, raw.bounds, raw.bold, raw.win),
                raw.center,
                Patch.NODE,
            )
            if (Color.alpha(patch.bg) == 0 && wantWin == -1) wantWin = raw.win
            ready.add(patch)
        }
        overlay.upsert(Patch.NODE, ready, dropMissing = !wasScrolling, hideUnmatched = wasScrolling)
        if (misses.isNotEmpty() && !prefs.paused()) translateLive(misses, blocked)
        val now = android.os.SystemClock.uptimeMillis()
        val missingColors = wantWin != -1
        val ocr = ShotPolicy.ocrShot(now, lastOcr, wasScrolling, prefs.paused(), prefs.ocr(), fromShot)
        val color = ShotPolicy.colorShot(
            now, latestBitmap != null, missingColors, lastScroll, shotAt, lastColorShot, wasScrolling, fromShot,
        )
        if (ocr || color) requestShot(runOcr = ocr, wantWin = if (color) wantWin else -1)
    }

    private fun translateLive(misses: List<Raw>, blocked: Boolean) {
        if (batteryLow()) return
        for (raw in misses) {
            if (!inFlight.add(raw.zh)) continue
            translator.translate(raw.zh).addOnSuccessListener { result ->
                val en = TextClean.present(result)
                if (en != null) cache.put(raw.zh, en)
                inFlight.remove(raw.zh)
                if (en == null || Chrome.skipText(raw.zh) || Chrome.skipEnglish(en)) return@addOnSuccessListener
                val source = raw.key.substringBefore('|').toIntOrNull() ?: Patch.NODE
                if (!blocked && prefs.contribute() && !app.translive.engine.PiiFilter.hasPii(raw.zh)) {
                    // Shared upload waits for a proxy. Nothing leaves the phone until then.
                }
                val previous = overlay.peek(raw.key)
                val colors = colorsFor(raw.bounds, previous, raw.win)
                overlay.put(
                    Patch(
                        raw.key,
                        en,
                        Rect(raw.bounds),
                        colors?.first ?: previous?.bg ?: Color.TRANSPARENT,
                        colors?.second ?: previous?.fg ?: Color.BLACK,
                        raw.textSizePx,
                        boldOf(raw.key, raw.bounds, raw.bold, raw.win),
                        raw.center,
                        source,
                    ),
                )
                if (colors == null) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (ShotPolicy.colorShot(now, latestBitmap != null, true, lastScroll, shotAt, lastColorShot, scrolling, false)) {
                        requestShot(runOcr = false, wantWin = raw.win)
                    }
                }
            }.addOnFailureListener { inFlight.remove(raw.zh) }
        }
    }

    private fun requestShot(runOcr: Boolean, wantWin: Int = -1) {
        val isRetry = suppressRetry
        suppressRetry = false
        if (!foreground || shotBusy || Build.VERSION.SDK_INT < 30) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!isRetry && runOcr && now - lastOcr < ShotPolicy.OCR_GAP_MS) {
            if (latestBitmap != null) return
        }
        if (runOcr && (batteryLow() || (prefs.dataSaver() && metered()))) {
            if (latestBitmap != null) return
        }
        val target = shotTarget(wantWin)
        val targetId = target?.first ?: -1
        val targetFrame = target?.second ?: Rect()
        shotBusy = true
        lastColorShot = now
        if (runOcr) lastOcr = now
        Log.d("TransLIVE", "shot ocr=$runOcr")
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                shotBusy = false
                val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                if (hardware == null) {
                    result.hardwareBuffer.close()
                    return
                }
                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, true)
                hardware.recycle()
                result.hardwareBuffer.close()
                if (bitmap == null) return
                val perWindow = Build.VERSION.SDK_INT >= 34 && targetId > 0
                latestBitmap = bitmap
                shotAt = android.os.SystemClock.uptimeMillis()
                shotWin = if (perWindow) targetId else -1
                shotFrame = if (perWindow) Rect(targetFrame) else Rect()
                if (!perWindow) maskOwnPatches(bitmap)
                val frame = Rect(shotFrame)
                val win = shotWin
                handler.post {
                    if (!::overlay.isInitialized || !foreground) return@post
                    settle(fromScroll = false, fromShot = true)
                    if (runOcr) readPhotoText(bitmap, win, frame)
                }
            }

            override fun onFailure(errorCode: Int) {
                shotBusy = false
                if (isRetry) return
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && foreground && !retryPending) {
                    retryPending = true
                    handler.postDelayed({
                        retryPending = false
                        suppressRetry = true
                        requestShot(runOcr, wantWin)
                    }, 1000)
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34 && targetId > 0) {
                takeScreenshotOfWindow(targetId, mainExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        } catch (e: RuntimeException) {
            shotBusy = false
            Log.w("TransLIVE", "screenshot refused", e)
        }
    }

    /** Window to capture: [wantWin] if it is one of the shopping app's windows, else the main window. */
    private fun shotTarget(wantWin: Int): Pair<Int, Rect>? {
        val pick = currentPick() ?: return null
        val w = pick.appWindows.firstOrNull { it.id == wantWin } ?: pick.appWindows.first { it.id == pick.windowId }
        return w.id to Rect(w.l, w.t, w.r, w.b)
    }

    private fun maskOwnPatches(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val paint = Paint()
        // Full-display shots include our overlay. Paint the stored background back over each label
        // (same 2 px pad OverlayView draws) so colour, bold and OCR never read our own English.
        for (patch in overlay.drawn()) {
            paint.color = patch.bg or 0xFF000000.toInt()
            canvas.drawRect(
                (patch.bounds.left - PATCH_PAD).toFloat(),
                (patch.bounds.top - PATCH_PAD).toFloat(),
                (patch.bounds.right + PATCH_PAD).toFloat(),
                (patch.bounds.bottom + PATCH_PAD).toFloat(),
                paint,
            )
        }
    }

    private fun readPhotoText(bitmap: Bitmap, win: Int, frame: Rect) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image).addOnSuccessListener { vision ->
            val pending = ArrayList<Raw>()
            for (block in vision.textBlocks) {
                for (line in block.lines) {
                    val zh = line.text.trim()
                    if (zh.count { it.code in 0x4E00..0x9FFF } < 2) continue
                    if (Chrome.skipText(zh)) continue
                    val box = line.boundingBox ?: continue
                    val screen = toScreen(box, bitmap, frame)
                    if (screen.width() < 8 || screen.height() < 8 || screen.height() > 160) continue
                    if (inStatusBar(screen)) continue
                    pending.add(Raw(nextSlot++, "", zh, screen, "", screen.height() * 0.75f, false, screen.width() < screen.height() * 4, win))
                }
            }
            val keyed = bindKeys(pending, Patch.OCR)
            val extra = ArrayList<Patch>()
            val misses = ArrayList<Raw>()
            for (raw in keyed) {
                val cached = cache.get(raw.zh)?.let { TextClean.present(it) }
                val hit = LookupChain.translate(raw.zh, prefs.glossary(), dict, if (cached != null) mapOf(raw.zh to cached) else emptyMap(), "NONE", false)
                val en = if (hit.layer == Layer.NONE) null else TextClean.present(hit.en)
                if (en == null || Chrome.skipEnglish(en)) {
                    if (en == null) misses.add(raw)
                    continue
                }
                val colors = colorsFor(raw.bounds, overlay.peek(raw.key), raw.win) ?: continue
                extra.add(Patch(raw.key, en, raw.bounds, colors.first, colors.second, raw.textSizePx, boldOf(raw.key, raw.bounds, false, raw.win), raw.center, Patch.OCR))
            }
            overlay.upsert(Patch.OCR, extra, dropMissing = true)
            if (misses.isNotEmpty() && foreground) translateLive(misses, false)
        }
    }

    private fun toScreen(box: Rect, bitmap: Bitmap, frame: Rect): Rect {
        val dm = resources.displayMetrics
        val sx: Float
        val sy: Float
        val ox: Int
        val oy: Int
        if (Build.VERSION.SDK_INT >= 34 && frame.width() > 0) {
            sx = frame.width().toFloat() / bitmap.width.coerceAtLeast(1)
            sy = frame.height().toFloat() / bitmap.height.coerceAtLeast(1)
            ox = frame.left
            oy = frame.top
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

    private fun colorsFor(bounds: Rect, previous: Patch?, win: Int): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < 30) {
            // No screenshots before Android 11: keep what we had, else a plain light patch.
            if (previous != null && Color.alpha(previous.bg) != 0) return previous.bg to previous.fg
            return FALLBACK_BG to FALLBACK_FG
        }
        val fresh = shotFresh(win)
        if (!fresh) {
            if (previous != null && Color.alpha(previous.bg) != 0) return previous.bg to previous.fg
            return null
        }
        return sample(bounds)
    }

    /** The latest shot shows [win] (a full-display shot shows every window) and nothing scrolled since. */
    private fun shotFresh(win: Int): Boolean =
        latestBitmap != null && shotAt > lastScroll && (shotWin == -1 || win == shotWin)

    private fun boldOf(key: String, bounds: Rect, fallback: Boolean, win: Int): Boolean {
        weight[key]?.let { return it }
        val measured = strokeBold(bounds, win) ?: return fallback
        weight[key] = measured
        return measured
    }

    private fun strokeBold(bounds: Rect, win: Int): Boolean? {
        if (!shotFresh(win)) return null
        val colors = sample(bounds) ?: return null
        val bitmap = latestBitmap ?: return null
        val originX = if (Build.VERSION.SDK_INT >= 34) shotFrame.left else 0
        val originY = if (Build.VERSION.SDK_INT >= 34) shotFrame.top else 0
        val left = (bounds.left - originX).coerceAtLeast(0)
        val top = (bounds.top - originY).coerceAtLeast(0)
        val right = (bounds.right - originX).coerceAtMost(bitmap.width)
        val bottom = (bounds.bottom - originY).coerceAtMost(bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 1 || h < 1) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)
        return BoldMeter.isBold(pixels, w, h, colors.first)
    }

    private fun bindKeys(found: List<Raw>, source: Int): List<Raw> {
        val existing = overlay.tracks(source).map { patch ->
            val parts = patch.key.split('|')
            val viewId = if (parts.size >= 4) parts[parts.size - 2] else ""
            val text = if (parts.size >= 4) parts.subList(1, parts.size - 2).joinToString("|") else ""
            KeyMatch.Item(patch.key, text, viewId, patch.bounds.top, patch.bounds.left, patch.bounds.height())
        }
        val nodes = found.map { KeyMatch.Node(it.slot, it.zh, it.viewId, it.bounds.top, it.bounds.left, it.bounds.height()) }
        val assigned = KeyMatch.assign(nodes, existing, pendingScrollDy)
        pendingScrollDy = 0
        return found.map { raw ->
            raw.copy(key = assigned[raw.slot] ?: overlay.mint(source, raw.zh, raw.viewId))
        }
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
        out.add(Raw(nextSlot++, "", raw, Rect(rect), viewId.orEmpty(), size, bold, center))
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
        stopAll()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun stopAll() {
        foreground = false
        retryPending = false
        suppressRetry = false
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
    }

    private data class Raw(
        val slot: Int,
        val key: String,
        val zh: String,
        val bounds: Rect,
        val viewId: String,
        val textSizePx: Float,
        val bold: Boolean,
        val center: Boolean,
        val win: Int = -1,
    )
}

private const val PATCH_PAD = 2 // OverlayView draws each patch 2 px larger on every side
private val FALLBACK_BG = Color.WHITE
private val FALLBACK_FG = Color.parseColor("#222222")

private fun PiiScreen(text: String) = app.translive.engine.PiiFilter.hasPii(text)
