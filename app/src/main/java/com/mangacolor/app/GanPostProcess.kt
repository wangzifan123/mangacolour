package com.mangacolor.app

import android.graphics.Bitmap
import java.util.stream.IntStream
import kotlin.math.max

/**
 * GanPostProcess — GAN 上色结果的纯 Kotlin 后处理管线。
 *
 * 不依赖 OpenCV / 任何原生库：sRGB<->CIELAB、box filter、guided filter 全部自实现。
 * 输入输出都是 [Bitmap]，无状态（只读常量表），可在任意线程调用。
 *
 * ## 三步处理
 *
 * **① L 通道保真（默认开启，效果最好）**
 * 转 CIELAB，把结果的 L 换成原图的 L，只保留结果的 a/b（ColorComic 的
 * "L-channel preservation for line fidelity"）。比正片叠底更彻底——连灰阶过渡都保住。
 *
 * **② 线条回贴（保底方案）**
 * `factor = 1 - strength * (1 - gray/255)`，与 `run_onnx.py` 的 `restore_lines` 同式。
 * 线条 100% 来自原图。注意：与 ① 同时开强度会叠加（①已保真 L，②再乘会过黑），
 * 所以默认 `lineStrength = 0`；单独用 ② 请用 [Params.multiplyFallback]。
 *
 * **③ 亮部保色（默认开启）**
 * `whiteThreshold = 234`、`neutralTransition = 42`（0-255 刻度）：**只有亮度 > 234
 * 才认为是纯白、才允许向中性褪色**，过渡带落在 234..276 而不是 192..234。
 * 这一步的方向有实测依据——把过渡带放在阈值之下，亮部色度只剩 64.2%，正是
 * "高光颜色被洗掉"的根因；放在阈值之上则保住 100%，同时纸白仍被清理。
 *
 * ## 内存与性能
 * 逐行并行；sRGB 线性化用 256 项精确查表，cbrt / gamma 用带线性插值的 LUT
 * （2048 / 4096 格，误差 < 0.01 级），避免逐像素 `Math.pow` / `Math.cbrt`。
 * 像素数据只保留 cp / op / L / a / b 五条平面（2.28 MP 约 46 MB），输出直接写回 cp。
 *
 * @see verify_postprocess.py 等价 Python 实现与数值一致性验证
 */
object GanPostProcess {

    // ======================================================================
    // 公开参数
    // ======================================================================

    /** 亮部保色的白色阈值（0-255）：只有亮于此值才允许褪色。 */
    const val WHITE_THRESHOLD = 234f

    /** 白色阈值之上的过渡带宽度（0-255）。 */
    const val NEUTRAL_TRANSITION = 42f

    /** guided filter 默认正则项（引导图归一化到 0..1 的单位）。 */
    const val DEFAULT_GUIDED_EPS = 4e-4f

    /** cel flattening 默认半径。 */
    const val DEFAULT_CEL_RADIUS = 8

    /**
     * guided filter 里判定"病态邻域"的方差下限。
     *
     * `varI = E[I²] - E[I]²` 是两个相近大数之差，理论上恒 ≥ 0；实测有 0.29% 的
     * 像素因浮点 round-off 落成**负数**（最小 -4.3e-12），此时 `covIp/varI`
     * 会把纯噪声放大成巨大系数。对这些像素直接取数学极限 a=0、b=mean_p
     * （退化为均值滤波）。真实纹理的局部方差中位数是 2.9e-2，远大于 0，
     * 因此该判定只清病态点，不伤正常纹理。
     */
    private const val VAR_EPS = 0.0

    /**
     * 一次性后处理参数。
     *
     * 默认组合 = ① + ③（L 保真 + 亮部保色），`lineStrength` 为 0 以免与 ① 叠加过黑。
     *
     * @param lFidelity        ① 的强度：0 = 完全用结果图的 L，1 = 完全用原图的 L
     * @param lineStrength     ② 的强度：0 = 不叠，1 = 完全按原图线稿压暗
     * @param brightPreserve   是否启用 ③
     * @param whiteThreshold   ③ 的白色阈值（0-255）
     * @param neutralTransition ③ 的过渡带宽度（0-255）
     * @param guidedRadius     以原图灰度引导对 a/b 做 guided filter 的半径；0 = 关闭
     * @param guidedEps        guided filter 的 eps
     * @param celFlatten       cel flattening 强度：0 = 关闭，1 = 完全取邻域均值
     * @param celRadius        cel flattening 的邻域半径
     * @param parallel         是否按行并行
     */
    data class Params(
        val lFidelity: Float = 1f,
        val lineStrength: Float = 0f,
        val brightPreserve: Boolean = true,
        val whiteThreshold: Float = WHITE_THRESHOLD,
        val neutralTransition: Float = NEUTRAL_TRANSITION,
        val guidedRadius: Int = 0,
        val guidedEps: Float = DEFAULT_GUIDED_EPS,
        val celFlatten: Float = 0f,
        val celRadius: Int = DEFAULT_CEL_RADIUS,
        val parallel: Boolean = true,
    ) {
        companion object {
            /** ② 的保底组合：纯正片叠底，不碰 LAB。等价于 run_onnx.py 的 restore_lines。 */
            fun multiplyFallback(strength: Float = 1f): Params = Params(
                lFidelity = 0f,
                lineStrength = strength,
                brightPreserve = false,
            )
        }
    }

    // ======================================================================
    // 公开 API
    // ======================================================================

    /**
     * 完整后处理：① L 通道保真 + ② 线条回贴 + ③ 亮部保色（强度由 [params] 决定）。
     *
     * @param colored  GAN 上色结果
     * @param original 原始线稿（尺寸不同会自动缩放对齐）
     * @return 新的 Bitmap（ARGB_8888），不改动入参
     */
    fun process(colored: Bitmap, original: Bitmap, params: Params = Params()): Bitmap {
        val w = colored.width
        val h = colored.height
        if (w <= 0 || h <= 0) return colored

        val n = w * h
        val src = alignOriginal(original, w, h)
        val cp = readPixels(colored, w, h)
        val op = readPixels(src, w, h)

        val lFid = params.lFidelity.coerceIn(0f, 1f)
        val lineStr = params.lineStrength.coerceIn(0f, 1f)
        val wantGuided = params.guidedRadius > 0
        val wantCel = params.celFlatten > 0f
        // 只有要动 L / a / b 时才走 LAB；纯正片叠底走快路径，省一次往返转换。
        val needLab = lFid > 0f || wantGuided || wantCel

        if (!needLab) {
            simplePass(cp, op, w, h, lineStr, params)
            return writePixels(cp, w, h)
        }

        // ---- pass A：原图 L + 结果 a/b ----
        val lFinal = FloatArray(n)
        val aCh = FloatArray(n)
        val bCh = FloatArray(n)
        val blend = lFid > 0f && lFid < 1f
        // guided filter / cel flatten 的引导图 = 原图灰度归一化；只有需要时才留这份平面
        val gray = if (wantGuided || wantCel) FloatArray(n) else null

        forEachRow(params.parallel, h) { y ->
            var i = y * w
            for (x in 0 until w) {
                val o = op[i]
                val or = (o shr 16) and 0xFF
                val og = (o shr 8) and 0xFF
                val ob = o and 0xFF
                val lr = LINEAR[or]
                val lg = LINEAR[og]
                val lb = LINEAR[ob]
                val gy = 0.2126729f * lr + 0.7151522f * lg + 0.0721750f * lb
                // 原图 L：只需 Y 一路，省掉 X/Z
                val lSrc = 116f * fOf(gy) - 16f
                if (gray != null) {
                    gray[i] = (or * 0.299f + og * 0.587f + ob * 0.114f) * INV_255
                }

                val c = cp[i]
                val cr = LINEAR[(c shr 16) and 0xFF]
                val cg = LINEAR[(c shr 8) and 0xFF]
                val cb = LINEAR[c and 0xFF]
                val fx = fOf((0.4124564f * cr + 0.3575761f * cg + 0.1804375f * cb) * INV_XN)
                val fy = fOf(0.2126729f * cr + 0.7151522f * cg + 0.0721750f * cb)
                val fz = fOf((0.0193339f * cr + 0.1191920f * cg + 0.9503041f * cb) * INV_ZN)
                val lDst = 116f * fy - 16f
                aCh[i] = 500f * (fx - fy)
                bCh[i] = 200f * (fy - fz)

                lFinal[i] = when {
                    lFid <= 0f -> lDst
                    blend -> lDst + (lSrc - lDst) * lFid
                    else -> lSrc
                }
                i++
            }
        }

        // ---- 可选：guided filter 清边 / cel flattening（都在 a/b 上做）----
        if (wantGuided || wantCel) {
            val g = gray!!
            if (wantGuided) {
                guidedFilterPair(g, aCh, bCh, w, h, params.guidedRadius, params.guidedEps, aCh, bCh)
            }
            if (wantCel) {
                val amt = params.celFlatten.coerceIn(0f, 1f)
                val fa = FloatArray(n)
                val fb = FloatArray(n)
                guidedFilterPair(g, aCh, bCh, w, h, max(1, params.celRadius),
                    params.guidedEps, fa, fb)
                for (i in 0 until n) {
                    aCh[i] += (fa[i] - aCh[i]) * amt
                    bCh[i] += (fb[i] - bCh[i]) * amt
                }
            }
        }

        // ---- pass B：L/a/b -> RGB -> 线条叠乘 -> 亮部保色（直接写回 cp）----
        forEachRow(params.parallel, h) { y ->
            var i = y * w
            for (x in 0 until w) {
                val fy2 = (lFinal[i] + 16f) * INV_116
                val fx2 = fy2 + aCh[i] * INV_500
                val fz2 = fy2 - bCh[i] * INV_200

                val xx = XN * finv(fx2)
                val yy = finv(fy2)
                val zz = ZN * finv(fz2)

                var r = 255f * gammaOf(3.2404542f * xx - 1.5371385f * yy - 0.4985314f * zz)
                var g = 255f * gammaOf(-0.9692660f * xx + 1.8760108f * yy + 0.0415560f * zz)
                var b = 255f * gammaOf(0.0556434f * xx - 0.2040259f * yy + 1.0572252f * zz)

                if (lineStr > 0f) {
                    val o = op[i]
                    val grayF = ((o shr 16) and 0xFF) * 0.299f +
                        ((o shr 8) and 0xFF) * 0.587f + (o and 0xFF) * 0.114f
                    val f = 1f - lineStr * (1f - grayF * INV_255)
                    r *= f
                    g *= f
                    b *= f
                }
                cp[i] = pack((cp[i] ushr 24), r, g, b, params)
                i++
            }
        }
        return writePixels(cp, w, h)
    }

    /** ① 单独可用：L 通道保真。 */
    fun replaceL(colored: Bitmap, original: Bitmap, amount: Float = 1f): Bitmap =
        process(colored, original, Params(lFidelity = amount, brightPreserve = false))

    /** ② 单独可用：线条回贴（等价于 run_onnx.py 的 restore_lines）。 */
    fun restoreLines(colored: Bitmap, original: Bitmap, strength: Float = 1f): Bitmap =
        process(colored, original, Params.multiplyFallback(strength))

    /** ③ 单独可用：亮部保色（不需要原图）。 */
    fun preserveBright(
        colored: Bitmap,
        whiteThreshold: Float = WHITE_THRESHOLD,
        neutralTransition: Float = NEUTRAL_TRANSITION,
    ): Bitmap = process(
        colored, colored,
        Params(
            lFidelity = 0f,
            brightPreserve = true,
            whiteThreshold = whiteThreshold,
            neutralTransition = neutralTransition,
        ),
    )

    /** 可选增强：以原图灰度引导，对 a/b 做 guided filter 清边。 */
    fun guidedFilterClean(
        colored: Bitmap,
        original: Bitmap,
        radius: Int = 4,
        eps: Float = DEFAULT_GUIDED_EPS,
    ): Bitmap = process(
        colored, original,
        Params(lFidelity = 1f, guidedRadius = radius, guidedEps = eps),
    )

    /** 可选增强：cel flattening，把色块从"渐变"拉成"平涂"。 */
    fun celFlatten(
        colored: Bitmap,
        original: Bitmap,
        radius: Int = DEFAULT_CEL_RADIUS,
        amount: Float = 0.7f,
    ): Bitmap = process(
        colored, original,
        Params(lFidelity = 1f, celFlatten = amount, celRadius = radius),
    )

    // ======================================================================
    // 快路径：不做 LAB，只做灰度叠乘 + 亮部保色
    // ======================================================================

    private fun simplePass(
        cp: IntArray, op: IntArray, w: Int, h: Int, lineStr: Float, params: Params,
    ) {
        forEachRow(params.parallel, h) { y ->
            var i = y * w
            for (x in 0 until w) {
                val o = op[i]
                val grayF = ((o shr 16) and 0xFF) * 0.299f +
                    ((o shr 8) and 0xFF) * 0.587f + (o and 0xFF) * 0.114f
                val c = cp[i]
                var r = ((c shr 16) and 0xFF).toFloat()
                var g = ((c shr 8) and 0xFF).toFloat()
                var b = (c and 0xFF).toFloat()
                if (lineStr > 0f) {
                    val f = 1f - lineStr * (1f - grayF * INV_255)
                    r *= f
                    g *= f
                    b *= f
                }
                cp[i] = pack((c ushr 24), r, g, b, params)
                i++
            }
        }
    }

    /** 亮部保色 + 打包成 ARGB。r/g/b 为 0-255 浮点。 */
    private fun pack(alpha: Int, rIn: Float, gIn: Float, bIn: Float, params: Params): Int {
        var r = rIn
        var g = gIn
        var b = bIn
        if (params.brightPreserve) {
            val lum = r * 0.299f + g * 0.587f + b * 0.114f
            val t = ((lum - params.whiteThreshold) / params.neutralTransition).coerceIn(0f, 1f)
            if (t > 0f) {
                // smoothstep：阈值处一阶连续，避免 234 附近出现色阶断层
                val wgt = t * t * (3f - 2f * t)
                r += (lum - r) * wgt
                g += (lum - g) * wgt
                b += (lum - b) * wgt
            }
        }
        return (alpha shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    }

    /**
     * 四舍五入到 0-255。
     *
     * 与 Java 端 `to8` 逐字一致：先 +0.5 再截断。注意顺序——先加 0.5 再比较
     * 255，可以保证 v=254.5 得到 255、v=254.4 得到 254，与 Math.round 同语义。
     */
    private fun to8(v: Float): Int {
        val x = v + 0.5f
        return if (x <= 0f) 0 else if (x >= 255f) 255 else x.toInt()
    }

    // ======================================================================
    // 位图读写
    // ======================================================================

    private fun readPixels(bmp: Bitmap, w: Int, h: Int): IntArray {
        val safe = if (bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
        } else bmp
        val px = IntArray(w * h)
        safe.getPixels(px, 0, w, 0, 0, w, h)
        return px
    }

    private fun writePixels(px: IntArray, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun alignOriginal(original: Bitmap, w: Int, h: Int): Bitmap =
        if (original.width == w && original.height == h) original
        else Bitmap.createScaledBitmap(original, w, h, true)

    // ======================================================================
    // 逐行并行
    // ======================================================================

    private inline fun forEachRow(parallel: Boolean, h: Int, crossinline body: (Int) -> Unit) {
        if (parallel && h >= 96) {
            IntStream.range(0, h).parallel().forEach { y -> body(y) }
        } else {
            var y = 0
            while (y < h) {
                body(y)
                y++
            }
        }
    }

    // ======================================================================
    // 颜色常量与 LUT
    // ======================================================================

    private const val INV_255 = 1f / 255f
    private const val INV_116 = 1f / 116f
    private const val INV_500 = 1f / 500f
    private const val INV_200 = 1f / 200f

    private const val XN = 0.95047f
    private const val ZN = 1.08883f
    private const val INV_XN = 1f / 0.95047f
    private const val INV_ZN = 1f / 1.08883f

    /** CIELAB 分段函数拐点 6/29 及其派生量（用 Double 算常量，保证与 Python 端同位）。 */
    private val DELTA_D = 6.0 / 29.0
    private val DELTA = DELTA_D.toFloat()
    private val DELTA3 = (DELTA_D * DELTA_D * DELTA_D).toFloat()
    private val K_F = (1.0 / (3.0 * DELTA_D * DELTA_D)).toFloat()
    private val DELTA_SQ3 = (3.0 * DELTA_D * DELTA_D).toFloat()
    private const val FOUR_29 = 4f / 29f

    private const val CBRT_N = 2048
    private const val GAMMA_N = 4096

    /** sRGB 8 bit -> 线性。只有 256 种输入，精确查表、零误差。 */
    private val LINEAR = FloatArray(256) { i ->
        val c = i / 255.0
        (if (c <= 0.04045) c / 12.92
        else Math.pow((c + 0.055) / 1.055, 2.4)).toFloat()
    }

    /** t in [0,1] -> cbrt(t)，带线性插值。 */
    private val CBRT_LUT = FloatArray(CBRT_N + 1) { i ->
        Math.cbrt(i.toDouble() / CBRT_N).toFloat()
    }

    /** 线性 -> sRGB gamma，带线性插值。 */
    private val GAMMA_LUT = FloatArray(GAMMA_N + 1) { i ->
        val c = i.toDouble() / GAMMA_N
        (if (c <= 0.0031308) c * 12.92
        else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055).toFloat()
    }

    private fun lerp(table: FloatArray, pos: Float, last: Int): Float {
        if (pos <= 0f) return table[0]
        if (pos >= last) return table[last]
        val i = pos.toInt()
        val f = pos - i
        return table[i] + (table[i + 1] - table[i]) * f
    }

    /** CIELAB 的 f(t)，t = X/Xn、Y/Yn、Z/Zn，定义域 [0, 1]。 */
    private fun fOf(t: Float): Float =
        if (t > DELTA3) lerp(CBRT_LUT, t * CBRT_N, CBRT_N) else t * K_F + FOUR_29

    /** CIELAB 的 f^-1(t)。解析式，比查表还省。 */
    private fun finv(t: Float): Float =
        if (t > DELTA) t * t * t else DELTA_SQ3 * (t - FOUR_29)

    /** 线性 -> sRGB（可为超色域的负值），内部钳到 0。 */
    private fun gammaOf(c: Float): Float =
        if (c <= 0f) 0f else lerp(GAMMA_LUT, c * GAMMA_N, GAMMA_N)

    // ======================================================================
    // box filter / guided filter（自实现，不依赖 OpenCV）
    // ======================================================================

    /**
     * (2r+1)^2 均值滤波，边界按**复制边缘**处理（等价 OpenCV 的 BORDER_REPLICATE）。
     *
     * 实现方式：先把源平面按 edge 复制扩出 r 圈，再在扩边后的平面里滑动一个
     * 完整的 (2r+1)^2 窗口。这样边缘像素的窗口与实心区一样是满窗，只是采样点被
     * 钳到边界——与 Python 端 `np.pad(mode="edge")` 的语义严格一致。
     *
     * （若改用「窗口在边界处收缩、只求实际覆盖部分」的写法，边界会变成另一种
     * 滤波器，实测两种语义在合成斜坡上差 27.9 级，因此必须选一种并两端对齐。）
     *
     * 积分图用 **Double** 累加：a/b 通道量级约 ±128，2.28 MP 的 Float 积分图会超出
     * 2^24 精确整数范围，四点求差时出现灾难性抵消。Double 与 Python 端 float64 同位。
     *
     * 允许 [dst] 与 [src] 是同一个数组：积分图先整体建完再写 dst，无读写交叠。
     */
    private fun boxFilter(
        src: FloatArray, w: Int, h: Int, radius: Int, dst: FloatArray,
        integral: DoubleArray,
    ) {
        if (radius <= 0) {
            src.copyInto(dst)
            return
        }
        val r = radius
        val pw = w + 2 * r
        val ph = h + 2 * r
        val iw = pw + 1
        // 前缀和只写「第 1..ph 行、第 1..pw 列」，第 0 行与第 0 列永远不被写到，
        // 因此 integral 只需在分配时清零一次，之后复用天然安全。
        for (py in 0 until ph) {
            // 复制边缘：越界行钳到首/末行
            val sy = if (py < r) 0 else if (py - r >= h) h - 1 else py - r
            var rowSum = 0.0
            val srcOff = sy * w
            val upOff = py * iw
            val curOff = upOff + iw
            for (px in 0 until pw) {
                val sx = if (px < r) 0 else if (px - r >= w) w - 1 else px - r
                rowSum += src[srcOff + sx]
                integral[curOff + px + 1] = integral[upOff + px + 1] + rowSum
            }
        }
        val k = 2 * r + 1
        val inv = 1.0 / (k * k.toDouble())
        for (y in 0 until h) {
            val top = y * iw            // 扩边后窗口左上角就是 (y, x)
            val bot = (y + k) * iw
            val dstOff = y * w
            for (x in 0 until w) {
                val x1 = x + k
                val sum = integral[bot + x1] - integral[top + x1] -
                    integral[bot + x] + integral[top + x]
                dst[dstOff + x] = (sum * inv).toFloat()
            }
        }
    }

    /** 单通道 guided filter：q = mean(a)*I + mean(b)（He et al. 2010）。 */
    private fun guidedFilterOne(
        guide: FloatArray, src: FloatArray, w: Int, h: Int, radius: Int, eps: Float,
        out: FloatArray, t1: FloatArray, t2: FloatArray, t3: FloatArray,
        integral: DoubleArray,
    ) {
        val n = w * h
        val meanI = t1
        val meanP = t2
        boxFilter(guide, w, h, radius, meanI, integral)
        boxFilter(src, w, h, radius, meanP, integral)

        val sq = t3        // 复用 t3 存 I*I
        val ip = out       // 复用 out 存 I*p（此刻 out 还是旧数据，可安全覆盖）
        for (i in 0 until n) {
            sq[i] = guide[i] * guide[i]
            ip[i] = guide[i] * src[i]
        }
        boxFilter(ip, w, h, radius, ip, integral)
        boxFilter(sq, w, h, radius, sq, integral)

        // ---- 关键：这 4 段统计量必须用 Double 计算 ----
        // varI = E[I^2] - E[I]^2、covIp = E[Ip] - E[I]E[p] 都是"两个大数相减得小量"。
        // 引导图 I 归一化在 0..1，E[I^2] 与 E[I]^2 量级接近，Float 只有 ~7 位有效数字，
        // 相减后 varI 的信噪比极差，甚至为负；covIp/varI 随之被放大到几十上百。
        // 实测 Float 版 a 通道输出散到 [-218, 155]，Double 版只有 [-23, 43]（正确范围）。
        for (i in 0 until n) {
            val mi = meanI[i].toDouble()
            val mp = meanP[i].toDouble()
            var varI = sq[i].toDouble() - mi * mi
            var covIp = ip[i].toDouble() - mi * mp
            // 平坦区（引导图局部无纹理）时，varI 与 covIp 都只是 round-off 噪声，
            // 二者之比无意义却会被 eps 进一步放大，导致同一算法在两次运行/两套实现
            // 之间出现跳变（实测 0.29% 像素偏差可达 17 级）。这里对病态点直接取
            // 数学极限 a=0、b=mean_p，即退化为纯均值滤波——既正确又免疫数值扰动。
            if (varI < VAR_EPS) {
                varI = 0.0
                covIp = 0.0
            }
            val a = covIp / (varI + eps.toDouble())
            sq[i] = a.toFloat()                 // 暂存 a
            meanI[i] = (mp - a * mi).toFloat()  // 暂存 b
        }
        boxFilter(sq, w, h, radius, sq, integral)         // mean(a)
        boxFilter(meanI, w, h, radius, meanI, integral)   // mean(b)
        for (i in 0 until n) {
            out[i] = sq[i] * guide[i] + meanI[i]
        }
    }

    /** 对 a/b 两个色度通道做 guided filter（共享临时缓冲；`aOut === aIn` 也安全）。 */
    private fun guidedFilterPair(
        guide: FloatArray, aIn: FloatArray, bIn: FloatArray,
        w: Int, h: Int, radius: Int, eps: Float, aOut: FloatArray, bOut: FloatArray,
    ) {
        val n = w * h
        val t1 = FloatArray(n)
        val t2 = FloatArray(n)
        val t3 = FloatArray(n)
        // 扩边后的平面是 (w+2r) x (h+2r)，积分图各边再多 1 行/列
        val integral = DoubleArray((w + 2 * radius + 1) * (h + 2 * radius + 1))
        guidedFilterOne(guide, aIn, w, h, radius, eps, aOut, t1, t2, t3, integral)
        guidedFilterOne(guide, bIn, w, h, radius, eps, bOut, t1, t2, t3, integral)
    }
}
