package com.mangacolor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * hint 涂色画布。
 *
 * 设计要点：
 *  1. 自己算 fitCenter 矩阵，**不依赖 ImageView 的 scaleType**。
 *     触摸点用这个变换的逆映射回原图坐标，避免"涂的位置和实际位置偏一截"。
 *  2. 笔迹一律以**原图坐标**保存（半径也换算成原图像素），
 *     这样旋转屏幕 / 切页 / 撤销重放都稳定，不会因显示尺寸变化而错位。
 *  3. 屏幕渲染要抗锯齿（好看），hint 导出必须硬边缘
 *     （hintMask 只能 0/1，抗锯齿会产生半透明杂边）。
 *  4. 笔迹点列表保存，撤销是 O(1) 回退，不需要整张位图快照。
 */
class HintCanvas @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** 一笔：同色同半径的连续笔迹，坐标全部是**原图像素** */
    private class Stroke(val color: Int, val radius: Float) {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val size get() = xs.size
        fun add(x: Float, y: Float) { xs.add(x); ys.add(y) }
    }

    private val strokes = ArrayList<Stroke>()

    /**
     * 笔迹读写锁。
     *
     * buildHint() 在后台线程跑（要扫描整张图），而触摸事件在 UI 线程，
     * 不加锁会撞上 ConcurrentModificationException。所有对 strokes 的
     * 增删读都走这把锁。
     */
    private val lock = Any()

    private var page: Bitmap? = null
    private var showResult: Bitmap? = null

    /** 当前画笔颜色（不透明） */
    var brushColor: Int = Color.rgb(244, 132, 95)
    /** 画笔半径，单位**屏幕像素**；落笔时按当前 scale 换算成原图像素 */
    var brushRadiusPx: Float = 12f
    /** 是否显示涂色覆盖层 */
    var hintVisible: Boolean = true
    /** 笔迹数量变化回调（刷新按钮可用状态） */
    var onStrokesChanged: (() -> Unit)? = null

    // ---- 变换 ----
    private val dst = RectF()
    private val matrix = Matrix()
    private var scale = 1f
    private var dx = 0f
    private var dy = 0f

    /** 尚未 layout（width/height 为 0）时为 true，触摸或绘制前兜底重算 */
    private var pendingFit = false

    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val overlayStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val overlayDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ---- 页面 ----

    fun setPage(bmp: Bitmap?) {
        page = bmp
        showResult = null
        synchronized(lock) { strokes.clear() }
        computeTransform()
        invalidate()
        onStrokesChanged?.invoke()
    }

    /** 显示上色结果；传 null 回到线稿继续涂 */
    fun setResult(bmp: Bitmap?) {
        showResult = bmp
        if (bmp != null) hintVisible = false
        invalidate()
    }

    fun hasResult(): Boolean = showResult != null

    private fun computeTransform() {
        val bmp = page
        if (bmp == null) {
            scale = 1f; dx = 0f; dy = 0f
            dst.setEmpty()
            return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        // 尚未 layout：先标记待计算，等 onSizeChanged 再算。
        // 这里不能沿用旧的 scale，否则触摸映射会用错的缩放比。
        if (vw <= 0f || vh <= 0f) {
            pendingFit = true
            return
        }
        pendingFit = false
        // fitCenter：取能完整显示原图的最小缩放比
        scale = min(vw / bmp.width, vh / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        dx = (vw - w) / 2f
        dy = (vh - h) / 2f
        dst.set(dx, dy, dx + w, dy + h)
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeTransform()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = page ?: return
        if (pendingFit && width > 0 && height > 0) computeTransform()
        if (dst.isEmpty) return
        canvas.drawBitmap(showResult ?: bmp, null, dst, pagePaint)
        if (!hintVisible) return

        // 覆盖层：按原图坐标画，靠 canvas concat 搬到屏幕
        canvas.save()
        canvas.concat(matrix)
        // 快照后遍历，避免 buildHint 在后台线程时并发修改
        val snapshot: List<Stroke> = synchronized(lock) { ArrayList(strokes) }
        for (s in snapshot) {
            val c = Color.argb(120, Color.red(s.color), Color.green(s.color), Color.blue(s.color))
            when (s.size) {
                0 -> {}
                1 -> {
                    overlayDot.color = c
                    canvas.drawCircle(s.xs[0], s.ys[0], s.radius, overlayDot)
                }
                else -> {
                    overlayStroke.color = c
                    overlayStroke.strokeWidth = s.radius * 2f
                    overlayDot.color = c
                    var px = s.xs[0]; var py = s.ys[0]
                    for (i in 1 until s.size) {
                        val x = s.xs[i]; val y = s.ys[i]
                        canvas.drawLine(px, py, x, y, overlayStroke)
                        canvas.drawCircle(x, y, s.radius, overlayDot)   // 补圆头，拐点才顺滑
                        px = x; py = y
                    }
                }
            }
        }
        canvas.restore()
    }

    // ---- 手势 ----

    private var active: Stroke? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = page ?: return false
        if (showResult != null) return false     // 看结果时不允许涂
        if (event.pointerCount > 1) {            // 多指忽略，避免误画
            active = null
            return true
        }
        // 极端情况（layout 前就收到触摸）：先补算变换，否则坐标全错
        if (pendingFit && width > 0 && height > 0) computeTransform()
        if (scale <= 0f) return false
        // 屏幕 -> 原图坐标（逆变换）
        val px = ((event.x - dx) / scale).coerceIn(0f, (bmp.width - 1).toFloat())
        val py = ((event.y - dy) / scale).coerceIn(0f, (bmp.height - 1).toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 半径按当前 scale 换算成原图像素；大图缩得小时换算值会很大，
                // 这里按图短边的 8% 封顶，避免一笔糊掉半个版面。
                val shortSide = min(bmp.width, bmp.height).toFloat()
                val r = (brushRadiusPx / scale).coerceIn(1f, shortSide * 0.08f)
                val s = Stroke(brushColor, r)
                s.add(px, py)
                synchronized(lock) { strokes.add(s) }
                active = s
                lastX = px; lastY = py
                invalidate()
                onStrokesChanged?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                val s = active
                if (s != null && abs(px - lastX) + abs(py - lastY) >= 1f) {
                    s.add(px, py)
                    lastX = px; lastY = py
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = null
                invalidate()
            }
        }
        return true
    }

    // ---- 编辑 ----

    fun undo(): Boolean {
        val removed = synchronized(lock) {
            if (strokes.isEmpty()) false else { strokes.removeAt(strokes.size - 1); true }
        }
        if (!removed) return false
        invalidate()
        onStrokesChanged?.invoke()
        return true
    }

    fun clearHints() {
        synchronized(lock) { strokes.clear() }
        invalidate()
        onStrokesChanged?.invoke()
    }

    fun hintStrokeCount(): Int = synchronized(lock) { strokes.size }

    // ---- hint 导出（严格按契约） -----------------------------------------

    /**
     * 生成 hintRGB（颜色层）与 hintMask（行优先，长度 w*h，值 0/1）。
     * 无笔迹时返回 (null, null)，调用方应把 null 传给 GanColorizer。
     */
    fun buildHint(): Pair<Bitmap?, ByteArray?> {
        val bmp = page ?: return null to null
        // 先取快照：这个函数在后台线程跑，触摸可能在 UI 线程追加笔迹。
        // 快照之后即使有新增笔迹，也只是这次 hint 少了最后一两笔，不会崩。
        val snapshot: List<Stroke> = synchronized(lock) {
            if (strokes.isEmpty()) ArrayList() else ArrayList(strokes)
        }
        if (snapshot.isEmpty()) return null to null

        val w = bmp.width
        val h = bmp.height
        val rgb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(rgb)

        // 硬边缘画笔：与 hintMask 的 0/1 语义一致，不产生半透明杂边
        val p = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val dot = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
        for (s in snapshot) {
            p.color = s.color
            p.strokeWidth = s.radius * 2f
            dot.color = s.color
            when (s.size) {
                0 -> {}
                1 -> c.drawCircle(s.xs[0], s.ys[0], s.radius, dot)
                else -> {
                    var px = s.xs[0]; var py = s.ys[0]
                    for (i in 1 until s.size) {
                        val x = s.xs[i]; val y = s.ys[i]
                        c.drawLine(px, py, x, y, p)
                        c.drawCircle(x, y, s.radius, dot)   // 补圆头，硬边缘下更连贯
                        px = x; py = y
                    }
                }
            }
        }

        val mask = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            rgb.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) {
                val argb = row[x]
                // 画过的地方 alpha 一定是 255；未画处 alpha=0
                if (Color.alpha(argb) != 0) mask[base + x] = 1
            }
        }
        return rgb to mask
    }

    /** 调试：当前变换参数 */
    fun debugTransform(): String = "scale=$scale dx=$dx dy=$dy"
}
