package app.translive.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.translive.R
import kotlin.math.roundToInt

data class Patch(
    val key: String,
    val text: String,
    val bounds: Rect,
    val bg: Int,
    val fg: Int,
    val textSizePx: Float,
    val bold: Boolean,
    val center: Boolean,
    val source: Int,
) {
    companion object {
        const val NODE = 0
        const val OCR = 1
    }
}

class OverlayView(context: Context) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var patches: List<Patch> = emptyList()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)
    }

    fun setPatches(next: List<Patch>) {
        val previous = patches
        patches = next
        if (previous.isEmpty() && next.isEmpty()) return
        for (patch in previous) dirty(patch.bounds)
        for (patch in next) dirty(patch.bounds)
    }

    private fun dirty(r: Rect) {
        invalidate(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4)
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        for (patch in patches) {
            if (Color.alpha(patch.bg) == 0) continue
            val r = patch.bounds
            bgPaint.color = patch.bg or 0xFF000000.toInt()
            canvas.drawRect(
                (r.left - 2).toFloat(),
                (r.top - 2).toFloat(),
                (r.right + 2).toFloat(),
                (r.bottom + 2).toFloat(),
                bgPaint,
            )
            val origin = if (patch.textSizePx > 0f) patch.textSizePx else r.height() * 0.75f
            val min = (origin * 0.7f).coerceAtLeast(9f * density)
            val maxLines = if (r.height() >= origin * 1.7f) 2 else 1
            textPaint.typeface = Typeface.create("sans-serif", if (patch.bold) Typeface.BOLD else Typeface.NORMAL)
            val laid = layout(patch.text, (r.width() - 2f).coerceAtLeast(4f), r.height().toFloat(), origin, min, maxLines)
            textPaint.color = patch.fg or 0xFF000000.toInt()
            textPaint.textSize = laid.first
            val metrics = textPaint.fontMetrics
            val lineH = metrics.descent - metrics.ascent
            val block = laid.second.size * lineH
            var baseline = r.top + ((r.height() - block) / 2f).coerceAtLeast(0f) - metrics.ascent
            textPaint.textAlign = if (patch.center) Paint.Align.CENTER else Paint.Align.LEFT
            for (line in laid.second) {
                if (baseline - lineH > r.bottom) break
                val x = if (patch.center) r.exactCenterX() else r.left + 1f
                canvas.drawText(line, x, baseline, textPaint)
                baseline += lineH
            }
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun layout(
        text: String,
        width: Float,
        height: Float,
        origin: Float,
        min: Float,
        maxLines: Int,
    ): Pair<Float, List<String>> {
        var size = origin.coerceAtMost(height * 0.92f).coerceAtLeast(min)
        textPaint.textSize = size
        var lines = wrap(text, width, maxLines)
        while (size > min && overflows(lines, width, height, size)) {
            size -= 0.5f
            textPaint.textSize = size
            lines = wrap(text, width, maxLines)
        }
        if (overflows(lines, width, height, size) && lines.isNotEmpty()) {
            lines = lines.toMutableList().also { it[it.lastIndex] = ellipsize(it.last(), width) }
        }
        return size to lines
    }

    private fun overflows(lines: List<String>, width: Float, height: Float, size: Float): Boolean {
        val metrics = textPaint.fontMetrics
        val lineH = metrics.descent - metrics.ascent
        return lines.size * lineH > height + 1f || lines.any { textPaint.measureText(it) > width }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clearWindowBackground(this)
    }

    private fun ellipsize(text: String, width: Float): String {
        if (textPaint.measureText(text) <= width) return text
        var cut = text
        while (cut.isNotEmpty() && textPaint.measureText("$cut…") > width) cut = cut.dropLast(1)
        return "$cut…"
    }

    private fun wrap(text: String, width: Float, maxLines: Int): List<String> {
        val words = if (text.any { it.code > 255 }) text.map { it.toString() } else text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val trial = if (current.isEmpty()) word else if (word.length == 1 && word[0].code > 255) current + word else "$current $word"
            if (textPaint.measureText(trial) <= width) current = trial
            else {
                if (current.isNotEmpty()) lines.add(current)
                current = word
                if (lines.size == maxLines) break
            }
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines.add(current)
        if (lines.isNotEmpty() && lines.size == maxLines) lines[lines.lastIndex] = ellipsize(lines.last(), width)
        if (lines.isEmpty()) lines.add(ellipsize(text, width))
        return lines
    }
}

class EdgeTabView(context: Context, private val host: EdgeHost) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }
    private var downY = 0f
    private var startY = 0
    private var moved = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clearWindowBackground(this)
    }

    override fun onDraw(canvas: Canvas) {
        val paused = host.paused()
        paint.color = if (paused) Color.parseColor("#E23D3D") else Color.parseColor("#12945F")
        val tabLeft = width - 52f
        canvas.drawRoundRect(tabLeft, 0f, width.toFloat() + 20f, height.coerceAtMost(160).toFloat(), 40f, 40f, paint)
        canvas.save()
        canvas.rotate(90f, width - 26f, 80f)
        canvas.drawText(if (paused) "Paused" else "Live", width - 70f, 88f, text)
        canvas.restore()
        if (!host.expanded()) return
        paint.color = Color.WHITE
        canvas.drawRoundRect(8f, 8f, tabLeft - 8f, height - 8f, 28f, 28f, paint)
        val ink = Paint(text).apply { color = Color.parseColor("#14204A"); textSize = 32f }
        canvas.drawText(if (paused) "Paused" else "Translating", 28f, 64f, ink)
        ink.textSize = 28f
        canvas.drawText(if (paused) "Tap: Resume" else "Tap: Pause", 28f, 120f, ink)
        canvas.drawText(if (host.ocr()) "Images: on" else "Images: off", 28f, 170f, ink)
        canvas.drawText(if (host.original()) "Original: on" else "Show original", 28f, 220f, ink)
        canvas.drawText("Long-press: report", 28f, 270f, ink)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                startY = host.y()
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.rawY - downY).roundToInt()
                if (kotlin.math.abs(dy) > 8) moved = true
                host.moveTo((startY + dy).coerceIn(40, 1600))
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val onPanel = host.expanded() && event.x < width - 52
                    if (!onPanel) host.toggle()
                    else host.tapPanel(event.y)
                }
                if (event.eventTime - event.downTime > 500 && !moved) host.reportLast()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

interface EdgeHost {
    fun paused(): Boolean
    fun expanded(): Boolean
    fun ocr(): Boolean
    fun original(): Boolean
    fun y(): Int
    fun moveTo(y: Int)
    fun toggle()
    fun tapPanel(y: Float)
    fun reportLast()
}

class OverlayController(base: Context) : EdgeHost {
    private val context = ContextThemeWrapper(base, R.style.Theme_TransLIVE_Overlay)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlay = OverlayView(context)
    private val edge = EdgeTabView(context, this)
    private var overlayAdded = false
    private var edgeAdded = false
    private var edgeY = 280
    private var open = false
    private var paused = false
    private var images = true
    private var showOriginal = false
    var onPause: (Boolean) -> Unit = {}
    var onOcr: (Boolean) -> Unit = {}
    var onOriginal: (Boolean) -> Unit = {}
    var onReport: () -> Unit = {}

    fun sync(paused: Boolean, images: Boolean, original: Boolean) {
        this.paused = paused
        this.images = images
        this.showOriginal = original
        edge.invalidate()
        render()
    }

    private val items = LinkedHashMap<String, Patch>()
    private val missing = HashMap<String, Int>()
    private val suppressed = HashSet<String>()
    private var edgeSig = ""
    private var nextId = 1
    private var live = false

    fun attached() = overlayAdded || edgeAdded

    fun setLive(on: Boolean) {
        live = on
        if (overlayAdded) render()
    }

    fun attach() {
        live = true
        if (!overlayAdded) {
            wm.addView(overlay, overlayParams())
            overlayAdded = true
        }
        ensureEdge(force = false)
        render()
    }

    fun mint(source: Int, zh: String, viewId: String) = "$source|$zh|$viewId|${nextId++}"

    fun tracks(source: Int): List<Patch> = items.values.filter { it.source == source }

    fun peek(key: String): Patch? = items[key]

    fun drawn(): List<Patch> = items.values.filter { Color.alpha(it.bg) != 0 && it.key !in suppressed }

    fun shift(container: Rect, dx: Int, dy: Int) {
        val screen = screen()
        val moveAll = container.isEmpty
        for ((key, patch) in items) {
            if (!moveAll && !inside(container, patch.bounds)) continue
            val moved = Rect(patch.bounds)
            moved.offset(dx, dy)
            items[key] = patch.copy(bounds = moved)
            if (!Rect.intersects(moved, screen)) suppressed.add(key) else suppressed.remove(key)
        }
        render()
    }

    fun concealInside(container: Rect) {
        val moveAll = container.isEmpty
        for ((key, patch) in items) {
            if (moveAll || inside(container, patch.bounds)) suppressed.add(key)
        }
        render()
    }

    /** Merge one source. Missing keys of that source are dropped only after two passes. */
    fun upsert(source: Int, next: List<Patch>, dropMissing: Boolean, hideUnmatched: Boolean = false) {
        val seen = HashSet<String>()
        val screen = screen()
        for (patch in next) {
            seen.add(patch.key)
            val old = items[patch.key]
            items[patch.key] = if (old != null && Color.alpha(patch.bg) == 0 && Color.alpha(old.bg) != 0) {
                patch.copy(bg = old.bg, fg = old.fg)
            } else {
                patch
            }
            missing.remove(patch.key)
            suppressed.remove(patch.key)
            if (!Rect.intersects(patch.bounds, screen)) suppressed.add(patch.key)
        }
        val unmatched = items.filter { it.value.source == source && it.key !in seen }.keys.toList()
        if (hideUnmatched) unmatched.forEach { suppressed.add(it) }
        if (dropMissing) {
            for (key in unmatched) {
                val count = (missing[key] ?: 0) + 1
                if (count >= 2) {
                    items.remove(key)
                    missing.remove(key)
                    suppressed.remove(key)
                } else {
                    missing[key] = count
                }
            }
        }
        render()
    }

    fun put(patch: Patch) {
        val old = items[patch.key]
        items[patch.key] = if (old != null && Color.alpha(patch.bg) == 0 && Color.alpha(old.bg) != 0) {
            patch.copy(bg = old.bg, fg = old.fg)
        } else {
            patch
        }
        missing.remove(patch.key)
        render()
    }

    private fun render() {
        if (!overlayAdded) return
        val visible = if (!live || paused || showOriginal) emptyList() else keepTight(items.values.filter { it.key !in suppressed })
        overlay.alpha = 1f
        overlay.setPatches(visible)
        if (edgeAdded) ensureEdge(force = false)
    }

    private fun keepTight(patches: List<Patch>): List<Patch> {
        val kept = ArrayList<Patch>()
        val ordered = patches.sortedWith(compareBy<Patch> { if (it.source == Patch.NODE) 0 else 1 }
            .thenBy { it.bounds.width().toLong() * it.bounds.height() }
            .thenBy { it.key })
        for (patch in ordered) {
            if (patch.text.isBlank()) continue
            if (Color.alpha(patch.bg) == 0) continue
            val w = patch.bounds.width()
            val h = patch.bounds.height()
            if (w < 2 || h < 2 || h > 220 || w * h > 260_000) continue
            if (kept.any { overlap(it.bounds, patch.bounds) > 0.45f }) continue
            kept.add(patch)
        }
        return kept
    }

    private fun overlap(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val iw = (right - left).coerceAtLeast(0)
        val ih = (bottom - top).coerceAtLeast(0)
        val inter = iw.toLong() * ih
        if (inter == 0L) return 0f
        val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height()).coerceAtLeast(1)
        return inter.toFloat() / smaller
    }

    fun hide() {
        live = false
        if (overlayAdded) {
            wm.removeView(overlay)
            overlayAdded = false
        }
        if (edgeAdded) {
            wm.removeView(edge)
            edgeAdded = false
            edgeSig = ""
        }
    }

    private fun screen() = Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)

    private fun inside(container: Rect, bounds: Rect): Boolean {
        return container.contains(bounds.centerX(), bounds.centerY()) || Rect.intersects(container, bounds)
    }

    private fun ensureEdge(force: Boolean) {
        val sig = "$open|$edgeY"
        if (!edgeAdded) {
            wm.addView(edge, edgeParams())
            edgeAdded = true
            edgeSig = sig
        } else if (force || sig != edgeSig) {
            wm.updateViewLayout(edge, edgeParams())
            edgeSig = sig
        }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun edgeParams() = WindowManager.LayoutParams(
        if (open) dp(220) else dp(56),
        if (open) dp(220) else dp(120),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        y = edgeY
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()

    override fun paused() = paused
    override fun expanded() = open
    override fun ocr() = images
    override fun original() = showOriginal
    override fun y() = edgeY
    override fun moveTo(y: Int) {
        edgeY = y
        if (edgeAdded) ensureEdge(force = true)
    }
    override fun toggle() {
        open = !open
        if (edgeAdded) ensureEdge(force = true)
        edge.invalidate()
    }
    override fun tapPanel(y: Float) {
        when {
            y < dp(90) -> onPause(!paused)
            y < dp(140) -> onOcr(!images)
            else -> onOriginal(!showOriginal)
        }
    }
    override fun reportLast() = onReport()
}

private fun clearWindowBackground(view: View) {
    var parent = view.parent
    while (parent is View) {
        parent.background = ColorDrawable(Color.TRANSPARENT)
        parent = parent.parent
    }
}
