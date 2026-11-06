package com.mangacolor.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GAN 上色推理（onnxruntime-android）。
 *
 * 与 Python 参考实现 `mangacolor-gan/run_onnx.py` 逐行对应，**所有数值口径都已实测**，
 * 改动前请先看 `研发进度与接口契约.md` 的 1.2 / 1.3 / 1.4 节。
 *
 * 输入张量 [1,5,H,W] float32：
 *  - ch0 灰度   **0~1**（(r/255) 原样）。绝对不能转 -1~1，否则整页发暗（亮度 157 → 93）。
 *  - ch1~3 颜色提示 ((c/255)-0.5)/0.5，无提示填 0。
 *  - ch4 hint mask 0/1，无提示填 0。
 *
 * 输出 [1,3,H,W] float32：raw **已经是 0~1**，直接 clip(0,1)。
 * 千万不要再 `*0.5f+0.5f`，否则画面发灰（亮度 91 → 173，std 78 → 39）。
 *
 * 预处理 resize_pad（照抄原仓库 utils/utils.py）：
 *  - 横图（h < w）：短边缩到 size*1.5（size=576 → 864）
 *  - 竖图（h >= w）：短边缩到 size（576）
 *  - 缩放算法 = PIL `Image.BOX`，即面积平均（INTER_AREA 等价），本文件手写实现
 *  - pad 到 32 的倍数，用 numpy `mode='maximum'`，其真实语义是
 *    **按整行 / 整列取最大值** 填充（不是简单复制边缘像素）；
 *    实测 bw1.jpg 两者差异明显：条带均值 254 vs 145，所以这里精确复刻 maximum 语义。
 *
 * 线程：`colorize()` 是阻塞调用，**禁止在主线程跑**（会直接抛异常）；
 * UI 请用 [colorizeSuspend] 或 [colorizeAsync]。
 */
object GanColorizer {

    private const val TAG = "GanColorizer"

    /** 默认模型（放在 app/src/main/assets/ 下）。 */
    const val DEFAULT_MODEL_ASSET = "manga-colorize-fp16.onnx"

    /** 短边基准尺寸，与 run_onnx.py 的 --size 默认值一致。 */
    const val DEFAULT_SIZE = 576

    /** 模型要求 H、W 必须是 32 的倍数。 */
    private const val ALIGNMENT = 32

    /** 横图时短边放大到 size * 3 / 2（576 → 864，跟 Python 的 size*1.5 完全一致）。 */
    private const val LANDSCAPE_FACTOR_NUM = 3
    private const val LANDSCAPE_FACTOR_DEN = 2

    /** 判定「模型文件看起来是完整的」的下限，防止下载/拷贝中断的残包被当成模型。 */
    private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L

    private val stateLock = Any()

    @Volatile private var ortEnv: OrtEnvironment? = null

    @Volatile private var ortSession: OrtSession? = null

    @Volatile private var inputTensorName: String = "input"

    @Volatile private var inputShortSide: Int = DEFAULT_SIZE

    /** 单线程后台执行器，供回调式 API 使用。 */
    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "gan-colorize").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    }

    // ------------------------------------------------------------------ 生命周期

    /** 是否已加载模型。 */
    val isReady: Boolean get() = ortSession != null

    /**
     * 加载模型（首次调用会解压 58.8MB assets 并建 session，较慢，请在后台线程调用）。
     *
     * @param modelAssetPath assets 内的相对路径，默认 [DEFAULT_MODEL_ASSET]
     * @param shortSide 短边基准尺寸，默认 [DEFAULT_SIZE]，一般不用改
     */
    fun init(context: Context, modelAssetPath: String = DEFAULT_MODEL_ASSET, shortSide: Int = DEFAULT_SIZE) {
        if (isReady) return
        val appContext = context.applicationContext ?: context
        val env = OrtEnvironment.getEnvironment()
        createSessionFromAsset(env, appContext, modelAssetPath, buildOptions())
        inputShortSide = shortSide
        Log.i(TAG, "模型就绪：$modelAssetPath（短边基准 $shortSide）")
    }

    /**
     * 从任意文件路径加载模型。
     * 用于「模型不进 assets、改为首次启动下载到私有目录」的分发方案。
     */
    fun initFromFile(modelFile: File, shortSide: Int = DEFAULT_SIZE) {
        if (isReady) return
        require(modelFile.isFile) { "模型文件不存在：${modelFile.absolutePath}" }
        require(modelFile.length() >= MIN_MODEL_BYTES) {
            "模型文件疑似不完整（${modelFile.length()} 字节），期望 >= $MIN_MODEL_BYTES"
        }
        createSession(OrtEnvironment.getEnvironment(), modelFile, buildOptions())
        inputShortSide = shortSide
    }

    /**
     * 释放 session。Activity onDestroy 时调用。
     *
     * 注意：OrtEnvironment 是 JVM 单例（ORT 1.11 起 close() 是 no-op，由 JVM shutdown hook 关），
     * 所以这里只关 session，不动 env。后台线程池也保留复用 —— 反复 init/release
     * （比如横竖屏切换）如果每次都重建线程池，反而容易把线程数堆上去。
     */
    fun release() {
        synchronized(stateLock) {
            ortSession?.close()
            ortSession = null
        }
    }

    // ------------------------------------------------------------------ 公开 API

    /**
     * 上色主入口（**阻塞**，必须在后台线程调用）。
     *
     * @param src 原图
     * @param hintRGB 可选颜色提示，必须与 [src] 同尺寸；无提示传 null
     * @param hintMask 可选，与 [hintRGB] 配套：src.width * src.height 个字节，1=有提示 0=无
     * @return 上色结果，尺寸与 [src] 相同（**未**做线条回贴，线条由 GanPostProcess.restoreLines 负责）
     */
    fun colorize(src: Bitmap, hintRGB: Bitmap? = null, hintMask: ByteArray? = null): Bitmap {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException(
                "GanColorizer.colorize() 不能在主线程调用（推理在 ARM CPU 上要几十秒）。" +
                    "请用 colorizeSuspend() / colorizeAsync()，或自己切到后台线程。"
            )
        }
        val session = ortSession ?: throw IllegalStateException(
            "GanColorizer 未初始化：请先调用 init(context) 或 initFromFile(file)"
        )
        val env = ortEnv ?: OrtEnvironment.getEnvironment()

        if (hintRGB != null && hintMask == null) {
            throw IllegalArgumentException("hintRGB 必须与 hintMask 同时提供（mask 为 null 时不知道哪些像素算提示）")
        }
        if (hintRGB != null && hintMask != null) {
            require(hintRGB.width == src.width && hintRGB.height == src.height) {
                "hintRGB 尺寸 ${hintRGB.width}x${hintRGB.height} 必须与 src ${src.width}x${src.height} 一致"
            }
            require(hintMask.size == src.width * src.height) {
                "hintMask 长度 ${hintMask.size} 必须等于 ${src.width * src.height}"
            }
        }

        val plan = buildPlan(src, inputShortSide, hintRGB, hintMask)
        val input = assembleInput(plan)
        val output = runSession(session, env, input, plan)
        return decodeOutput(output, plan, src.width, src.height)
    }

    /** 挂起版 [colorize]，自动切到 Dispatchers.Default。Activity/ViewModel 推荐用这个。 */
    suspend fun colorizeSuspend(
        src: Bitmap,
        hintRGB: Bitmap? = null,
        hintMask: ByteArray? = null,
    ): Bitmap = withContext(Dispatchers.Default) { colorize(src, hintRGB, hintMask) }

    /** 回调版 [colorize]，后台单线程执行，回调也在后台线程（UI 更新请自己 post 回主线程）。 */
    fun colorizeAsync(
        src: Bitmap,
        hintRGB: Bitmap? = null,
        hintMask: ByteArray? = null,
        onSuccess: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        worker.execute {
            try {
                onSuccess(colorize(src, hintRGB, hintMask))
            } catch (t: Throwable) {
                Log.e(TAG, "上色失败", t)
                onError(t)
            }
        }
    }

    // ------------------------------------------------------------------ 预处理

    /** 一次推理用到的中间数据，全部按「resize_pad 之后的完整画布」组织。 */
    private class Plan(
        /** 灰度 0~1，尺寸 fullW * fullH */
        val gray: FloatArray,
        /** 颜色提示（已乘 mask），无提示时为 null */
        val hintColor: FloatArray?,
        /** hint mask 0/1，无提示时为 null */
        val hintMask: FloatArray?,
        /** padding 后的宽（32 的倍数） */
        val fullW: Int,
        /** padding 后的高（32 的倍数） */
        val fullH: Int,
        /** 右侧补了多少列（横图） */
        val padRight: Int,
        /** 底部补了多少行（竖图） */
        val padBottom: Int,
        /** 去掉 padding 的真实推理尺寸 */
        val resizedW: Int,
        val resizedH: Int,
    )

    /**
     * 复刻 run_onnx.py 的 resize_pad + build_input 前半段。
     *
     * 注意 Python 里 `arr = np.asarray(Image.open(...).convert('RGB'))`，resize 之后取
     * `img[:, :, :1]` 即 **R 通道**（不是亮度）。黑白漫画 R=G=B 所以等价，
     * 但为了和参考实现逐位一致，这里同样取 R 通道。
     */
    private fun buildPlan(src: Bitmap, size: Int, hintRGB: Bitmap?, hintMask: ByteArray?): Plan {
        val srcW = src.width
        val srcH = src.height
        require(srcW > 0 && srcH > 0) { "src 尺寸非法：${srcW}x$srcH" }

        // --- 1) 目标尺寸（横图短边 -> size*1.5，竖图短边 -> size）
        val resizedW: Int
        val resizedH: Int
        if (srcH < srcW) {
            val shortSide = size * LANDSCAPE_FACTOR_NUM / LANDSCAPE_FACTOR_DEN
            val ratio = srcH.toDouble() / shortSide.toDouble()
            resizedW = ceil(srcW / ratio).toInt()
            resizedH = shortSide
        } else {
            val ratio = srcW.toDouble() / size.toDouble()
            resizedW = size
            resizedH = ceil(srcH / ratio).toInt()
        }
        require(resizedW > 0 && resizedH > 0) { "resize 后尺寸非法：${resizedW}x$resizedH" }

        // --- 2) pad 到 32 的倍数（只有一个方向会有 padding）
        val padRight = (ALIGNMENT - resizedW % ALIGNMENT) % ALIGNMENT
        val padBottom = (ALIGNMENT - resizedH % ALIGNMENT) % ALIGNMENT
        val fullW = resizedW + padRight
        val fullH = resizedH + padBottom

        // --- 3) 缩放（面积平均 = PIL BOX）后取 R 通道当灰度
        val redSmall = resamplePlane(readChannel(src, 16), srcW, srcH, resizedW, resizedH)

        // --- 4) 灰度 0~1 + maximum 模式 padding
        val gray = FloatArray(fullW * fullH)
        for (y in 0 until resizedH) {
            val srcOff = y * resizedW
            val dstOff = y * fullW
            for (x in 0 until resizedW) {
                gray[dstOff + x] = redSmall[srcOff + x] / 255f
            }
        }
        if (padRight > 0) {
            // numpy mode='maximum' 沿 axis=1：每一行用自己的行最大值填充
            for (y in 0 until resizedH) {
                var rowMax = 0f
                val srcOff = y * resizedW
                for (x in 0 until resizedW) {
                    val v = redSmall[srcOff + x]
                    if (v > rowMax) rowMax = v
                }
                val fill = rowMax / 255f
                val dstOff = y * fullW
                for (x in resizedW until fullW) gray[dstOff + x] = fill
            }
        }
        if (padBottom > 0) {
            // numpy mode='maximum' 沿 axis=0：每一列用自己的列最大值填充
            for (x in 0 until resizedW) {
                var colMax = 0f
                for (y in 0 until resizedH) {
                    val v = redSmall[y * resizedW + x]
                    if (v > colMax) colMax = v
                }
                val fill = colMax / 255f
                for (y in resizedH until fullH) gray[y * fullW + x] = fill
            }
        }

        // --- 5) 颜色提示（无提示 -> null，组装时填 0）
        var hintColor: FloatArray? = null
        var hintMaskOut: FloatArray? = null
        if (hintRGB != null && hintMask != null) {
            val hr = readChannel(hintRGB, 16)
            val hg = readChannel(hintRGB, 8)
            val hb = readChannel(hintRGB, 0)

            // mask 转 0/1 浮点。颜色用 mask 加权平均：resample(c*m) / resample(m)，
            // 直接对颜色做面积平均会把区域外的黑色混进来，提示色边缘会发暗。
            val maskBin = FloatArray(hintMask.size) { if (hintMask[it].toInt() == 0) 0f else 1f }
            val coverage = resamplePlaneF(maskBin, srcW, srcH, resizedW, resizedH)
            val hrW = resamplePlaneF(applyMask(hr, maskBin), srcW, srcH, resizedW, resizedH)
            val hgW = resamplePlaneF(applyMask(hg, maskBin), srcW, srcH, resizedW, resizedH)
            val hbW = resamplePlaneF(applyMask(hb, maskBin), srcW, srcH, resizedW, resizedH)

            // 注意：hint 的 padding 区域**必须保持 0**（0 = 此处没有提示），
            // 不能像 ch0 灰度那样用 maximum 复制边缘 —— 两者语义不同。
            // 下面只写 resizedW x resizedH 区域，右侧/底部 padding 自动保持 0，
            // 与 Python 参考实现一致（参考实现也是在 resize_pad 之后的画布上以 0 为底绘制提示）。
            val color = FloatArray(fullW * fullH * 3)
            val mask = FloatArray(fullW * fullH)
            for (y in 0 until resizedH) {
                val srcOff = y * resizedW
                val dstOff = y * fullW
                for (x in 0 until resizedW) {
                    val i = srcOff + x
                    // 覆盖率只要 >0 就算有提示：下行采样时细笔画平均后只剩百分之几，
                    // 用 0.5 阈值会被整条抹掉，用户会觉得「涂了没反应」。
                    val cov = coverage[i]
                    if (cov <= 0f) continue
                    val inv = 1f / cov
                    // 加权平均还原成 0~255 的真实颜色，再套 ((c/255)-0.5)/0.5 == c/127.5 - 1
                    val cr = (hrW[i] * inv).coerceIn(0f, 255f)
                    val cg = (hgW[i] * inv).coerceIn(0f, 255f)
                    val cb = (hbW[i] * inv).coerceIn(0f, 255f)
                    color[(dstOff + x) * 3 + 0] = cr / 127.5f - 1f
                    color[(dstOff + x) * 3 + 1] = cg / 127.5f - 1f
                    color[(dstOff + x) * 3 + 2] = cb / 127.5f - 1f
                    mask[dstOff + x] = 1f
                }
            }
            hintColor = color
            hintMaskOut = mask
        }

        return Plan(
            gray = gray,
            hintColor = hintColor,
            hintMask = hintMaskOut,
            fullW = fullW,
            fullH = fullH,
            padRight = padRight,
            padBottom = padBottom,
            resizedW = resizedW,
            resizedH = resizedH,
        )
    }

    /** 取出 Bitmap 的某个颜色通道（shift=16/8/0 对应 R/G/B），返回 w*h 的字节数组。 */
    private fun readChannel(bmp: Bitmap, shift: Int): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val off = y * w
            for (x in 0 until w) {
                out[off + x] = ((row[x] shr shift) and 0xFF).toByte()
            }
        }
        return out
    }

    /**
     * 面积平均缩放（= PIL `Image.BOX` / OpenCV INTER_AREA），可分离两遍实现，
     * 边缘像素按覆盖面积加权，最后四舍五入回整数，尽量贴近 PIL 的 uint8 结果。
     *
     * 用于灰度通道（必须是 0~255 的整数，才和 Python 的 uint8 中间结果对得上）。
     */
    private fun resamplePlane(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val floats = FloatArray(src.size) { (src[it].toInt() and 0xFF).toFloat() }
        val out = resamplePlaneF(floats, sw, sh, dw, dh)
        for (i in out.indices) out[i] = out[i].roundToInt().toFloat()
        return out
    }

    /**
     * [resamplePlane] 的浮点内核，**不做取整**。
     *
     * hint 的 mask 加权平均必须用它：加权和常常小于 1，取整会直接变成 0，
     * 提示色会被算成纯黑（-1），模型收到的提示就废了。
     *
     * 算法 = PIL `Image.BOX` 的**装箱**语义（不是数学意义上的精确面积加权）：
     * 输出像素 o 对应源区间 `[round(o*r), round((o+1)*r))`，区间内各像素**等权**平均。
     *
     * 这一条是反推出来的，实测对比过两种方案（bw1/bw2/bw3 三张图）：
     *  - 装箱 + 精确 1/n 权重：max 误差 1.0 / 105 / 103，>2 的像素 0% / 0.27% / 0.08%
     *  - 装箱 + PIL 式 8bit 定点权重 round(255/n)/255：权重和会到 257（越界），且 >2 像素高达 3%
     * 所以这里用精确权重，效果反而更贴近 PIL 的实际输出。
     */
    private fun resamplePlaneF(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh) {
            return src.copyOf()
        }
        val tmp = FloatArray(dw * sh)
        val scaleX = sw.toDouble() / dw.toDouble()
        for (y in 0 until sh) {
            val rowOff = y * sw
            for (x in 0 until dw) {
                val lo = floor(x * scaleX + 0.5).toInt()
                var hi = floor((x + 1) * scaleX + 0.5).toInt()
                if (hi <= lo) hi = lo + 1
                val start = max(0, lo)
                val end = min(hi, sw)
                val count = end - start
                if (count <= 0) {
                    tmp[y * dw + x] = src[rowOff + min(max(0, lo), sw - 1)]
                } else {
                    var acc = 0f
                    for (i in start until end) acc += src[rowOff + i]
                    tmp[y * dw + x] = acc / count
                }
            }
        }

        val out = FloatArray(dw * dh)
        val scaleY = sh.toDouble() / dh.toDouble()
        for (x in 0 until dw) {
            for (y in 0 until dh) {
                val lo = floor(y * scaleY + 0.5).toInt()
                var hi = floor((y + 1) * scaleY + 0.5).toInt()
                if (hi <= lo) hi = lo + 1
                val start = max(0, lo)
                val end = min(hi, sh)
                val count = end - start
                out[y * dw + x] = if (count <= 0) {
                    tmp[min(max(0, lo), sh - 1) * dw + x]
                } else {
                    var acc = 0f
                    for (i in start until end) acc += tmp[i * dw + x]
                    acc / count
                }
            }
        }
        return out
    }

    /** 颜色通道按 mask 归一化到 0~1 再置零，配合 [resamplePlaneF] 做 mask 加权平均。 */
    private fun applyMask(channel: ByteArray, mask: FloatArray): FloatArray {
        val out = FloatArray(channel.size)
        for (i in channel.indices) {
            if (mask[i] != 0f) out[i] = (channel[i].toInt() and 0xFF).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------------ 推理

    /** 组装 [1,5,H,W] float32 的 NCHW 输入。 */
    private fun assembleInput(plan: Plan): FloatArray {
        val n = plan.fullW * plan.fullH
        val input = FloatArray(5 * n)
        val hint = plan.hintColor
        val mask = plan.hintMask
        for (i in 0 until n) {
            input[i] = plan.gray[i]                    // ch0 灰度 0~1
            if (hint != null && mask != null && mask[i] != 0f) {
                val c = i * 3
                input[n + i] = hint[c]                 // ch1 R'
                input[2 * n + i] = hint[c + 1]         // ch2 G'
                input[3 * n + i] = hint[c + 2]         // ch3 B'
                input[4 * n + i] = 1f                  // ch4 hint mask
            }
            // 无提示：ch1~4 保持 0（不干预，让模型自由发挥）
        }
        return input
    }

    private fun runSession(
        session: OrtSession,
        env: OrtEnvironment,
        input: FloatArray,
        plan: Plan,
    ): FloatArray {
        val shape = longArrayOf(1, 5, plan.fullH.toLong(), plan.fullW.toLong())
        val n = plan.fullW * plan.fullH
        val out = FloatArray(3 * n)

        // 注意：OnnxTensor / OrtSession.Result 只实现 java.lang.AutoCloseable，
        // 不是 java.io.Closeable，所以不能用 Kotlin 的 `.use {}`（那要求 Closeable），
        // 必须手写 try/finally，否则编译不过。
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
        try {
            val result = session.run(mapOf(inputTensorName to tensor))
            try {
                val outTensor = result[0] as OnnxTensor
                val buffer = outTensor.floatBuffer
                buffer.rewind()
                buffer.get(out)
            } finally {
                result.close()
            }
        } finally {
            tensor.close()
        }
        return out
    }

    /**
     * 后处理：clip(0,1) -> 去掉 padding -> 缩回原图尺寸。
     * **不做** *0.5+0.5（模型 raw 已经是 0~1）。
     */
    private fun decodeOutput(
        out: FloatArray,
        plan: Plan,
        srcW: Int,
        srcH: Int,
    ): Bitmap {
        val n = plan.fullW * plan.fullH
        val pixels = IntArray(n)
        for (i in 0 until n) {
            val r = (out[i].coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (out[n + i].coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (out[2 * n + i].coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val full = Bitmap.createBitmap(plan.fullW, plan.fullH, Bitmap.Config.ARGB_8888)
        full.setPixels(pixels, 0, plan.fullW, 0, 0, plan.fullW, plan.fullH)

        val needCrop = plan.padRight > 0 || plan.padBottom > 0
        val cropped = if (needCrop) {
            Bitmap.createBitmap(full, 0, 0, plan.resizedW, plan.resizedH)
        } else {
            full
        }
        if (cropped !== full) full.recycle()

        return if (cropped.width != srcW || cropped.height != srcH) {
            // Python 用 LANCZOS，这里用系统双线性（差异在 1~2 个灰阶，视觉无感）
            val scaled = Bitmap.createScaledBitmap(cropped, srcW, srcH, true)
            if (scaled !== cropped) cropped.recycle()
            scaled
        } else {
            cropped
        }
    }

    // ------------------------------------------------------------------ 模型装载

    /**
     * 从 assets 装载模型：先把模型落到私有目录，再用文件路径建 session。
     *
     * 为什么不用「assets + ByteBuffer 零拷贝」的写法：
     * 那条路依赖 `noCompress += "onnx"` 生效后 `assets.openFd()` 可用，
     * 而且 `FileInputStream(fd).channel` 一关就会连带关掉底层 fd，
     * 不同 ROM / AGP 版本上行为不一致（映射区可能失效）。
     * 模型是 58.8MB 的一次性文件，拷贝耗时 0.3~1 秒，完全可接受，
     * 换来的是各机型都能稳定加载。
     *
     * 私有目录里的副本会保留，第二次启动直接复用，不重复拷贝。
     */
    private fun createSessionFromAsset(
        env: OrtEnvironment,
        context: Context,
        assetPath: String,
        options: OrtSession.SessionOptions,
    ) {
        synchronized(stateLock) {
            if (ortSession != null) return@synchronized

            // 先拷贝到私有目录：这是最稳的路径，APK 里 assets 的 openFd()
            // 只有在 noCompress 生效时才可用，而且不同 ROM 的实现在映射零长度 /
            // 非对齐文件时偶尔会出问题。58.8MB 的拷贝大约 0.3~1 秒，一次性的。
            val modelFile = try {
                materializeAsset(context, assetPath)
            } catch (e: Throwable) {
                throw IllegalStateException("加载模型失败：$assetPath", e)
            }
            createSession(env, modelFile, options)
        }
    }

    /**
     * 把 assets 里的模型拷到私有目录再用路径建 session（回退路径）。
     * 不直接读 byte[] 是因为 58.8MB 一次性进 Java 堆容易被低端机 OOM。
     */
    private fun materializeAsset(context: Context, assetPath: String): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建模型目录：${dir.absolutePath}")
        }
        val target = File(dir, assetPath.substringAfterLast('/'))
        if (target.isFile && target.length() >= MIN_MODEL_BYTES) {
            return target
        }

        val partial = File(dir, target.name + ".part")
        try {
            context.assets.open(assetPath).use { input ->
                partial.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE * 16)
                }
            }
        } catch (e: FileNotFoundException) {
            throw IllegalStateException(
                "assets 里没有模型 $assetPath。模型 58.8MB 不进 git，" +
                    "请先运行 ./gradlew downloadGanModel（或把模型手动放到 app/src/main/assets/）",
                e
            )
        }
        if (partial.length() < MIN_MODEL_BYTES) {
            partial.delete()
            throw IllegalStateException("assets 里的 $assetPath 只有 ${partial.length()} 字节，疑似损坏")
        }
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("无法覆盖旧模型：${target.absolutePath}")
        }
        if (!partial.renameTo(target)) {
            throw IllegalStateException("无法重命名模型文件到 ${target.absolutePath}")
        }
        return target
    }

    private fun buildOptions(): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        val threads = workerThreads()
        options.setIntraOpNumThreads(threads)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        return options
    }

    private fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        options: OrtSession.SessionOptions,
    ) {
        synchronized(stateLock) {
            if (ortSession != null) return@synchronized
            val session = env.createSession(modelFile.absolutePath, options)
            inputTensorName = session.inputNames.firstOrNull() ?: "input"
            ortEnv = env
            ortSession = session
            Log.i(TAG, "session 建立完成（文件路径）input=$inputTensorName threads=${workerThreads()}")
        }
    }

    private fun workerThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
}
