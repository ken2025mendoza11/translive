package app.translive.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.translive.R
import kotlin.math.roundToInt

data class Patch(val text: String, val bounds: Rect, val bg: Int, val fg: Int)

class OverlayView(context: Context) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private var patches: List<Patch> = emptyList()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)
    }

    fun setPatches(next: List<Patch>) {
        patches = next
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clearWindowBackground(this)
    }

    override fun onDraw(canvas: Canvas) {
        for (patch in patches) {
            bgPaint.color = patch.bg or 0xFF000000.toInt()
            val r = patch.bounds
            canvas.drawRoundRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), 8f, 8f, bgPaint)
            val width = (r.width() - 8f).coerceAtLeast(4f)
            val height = r.height().toFloat().coerceAtLeast(4f)
            val laid = layout(patch.text, width, height)
            textPaint.color = patch.fg
            textPaint.textSize = laid.first
            val block = laid.second.size * laid.first * 1.12f
            var y = r.top + ((height - block) / 2f).coerceAtLeast(0f) + laid.first
            for (line in laid.second) {
                if (y > r.bottom + 2) break
                canvas.drawText(line, r.left + 4f, y, textPaint)
                y += laid.first * 1.12f
            }
        }
    }

    private fun layout(text: String, width: Float, height: Float): Pair<Float, List<String>> {
        var size = (height * 0.72f).coerceIn(11f, 36f)
        textPaint.textSize = size
        var lines = wrap(text, width, 3)
        while (size > 11f && (lines.size * size * 1.12f > height || lines.any { textPaint.measureText(it) > width })) {
            size -= 1f
            textPaint.textSize = size
            lines = wrap(text, width, 3)
        }
        return size to lines
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
    }

    private var current = emptyList<Patch>()

    fun showPatches(patches: List<Patch>) {
        current = patches
        render()
    }

    fun addPatches(more: List<Patch>) {
        if (more.isEmpty()) return
        current = current + more
        render()
    }

    private fun render() {
        val visible = if (paused || showOriginal) emptyList() else keepTight(current)
        if (visible.isEmpty()) {
            hideOverlay()
        } else {
            overlay.setPatches(visible)
            if (!overlayAdded) {
                wm.addView(overlay, overlayParams())
                overlayAdded = true
            }
        }
        ensureEdge()
    }

    private fun keepTight(patches: List<Patch>): List<Patch> {
        val kept = ArrayList<Patch>()
        val ordered = patches.sortedBy { it.bounds.width().toLong() * it.bounds.height() }
        for (patch in ordered) {
            if (patch.text.isBlank()) continue
            val w = patch.bounds.width()
            val h = patch.bounds.height()
            if (w < 2 || h < 2 || h > 420 || w * h > 700_000) continue
            if (kept.any { overlap(it.bounds, patch.bounds) > 0.4f }) continue
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
        hideOverlay()
        if (edgeAdded) {
            wm.removeView(edge)
            edgeAdded = false
        }
    }

    private fun hideOverlay() {
        if (overlayAdded) {
            wm.removeView(overlay)
            overlayAdded = false
        }
    }

    private fun ensureEdge() {
        if (!edgeAdded) {
            wm.addView(edge, edgeParams())
            edgeAdded = true
        } else {
            wm.updateViewLayout(edge, edgeParams())
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
        if (edgeAdded) wm.updateViewLayout(edge, edgeParams())
    }
    override fun toggle() {
        open = !open
        if (edgeAdded) wm.updateViewLayout(edge, edgeParams())
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
