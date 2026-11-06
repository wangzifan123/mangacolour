package com.mangacolor.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 上色算法核心。与 mangacolor.py 中的实现一一对应，参数语义完全一致。
 *
 * 流程：切块 -> 逐块 img2img -> 二维斜坡加权融合 -> 原图灰度正片叠底回贴线条
 */
object Colorizer {

    const val DEFAULT_PROMPT =
        "full color, anime coloring, cel shading, flat colors, " +
            "vivid saturated colors, colorful"

    const val DEFAULT_NEGATIVE =
        "monochrome, grayscale, black and white, desaturated, " +
            "muted colors, sketch, lineart, low quality, blurry"

    /**
     * 官方推荐的 img2img denoise（0.75–0.85）。
     * 见 https://ld-guide.chino.icu/models/parameters
     * 之前用 0.45 属于 "small fixes, light restyling"，根本不够铺色。
     */
    const val DEFAULT_DENOISE = 0.80f

    /** 在一条轴上算出所有块起点，保证完整覆盖且不越界。 */
    fun axisPositions(total: Int, tile: Int, overlap: Int): List<Int> {
        if (total <= tile) return listOf(0)
        val step = max(1, tile - overlap)
        val out = ArrayList<Int>()
        var x = 0
        while (x + tile < total) {
            out.add(x)
            x += step
        }
        out.add(total - tile)   // 贴住边缘，避免留下细缝
        return out.distinct()
    }

    /** 二维融合权重：边缘低、中心高，让重叠区平滑过渡。 */
    private fun rampWeights(tile: Int, overlap: Int): FloatArray {
        val w = FloatArray(tile * tile)
        if (overlap <= 0) {
            java.util.Arrays.fill(w, 1f)
            return w
        }
        val ramp = FloatArray(overlap) { i -> (i + 1).toFloat() / (overlap + 1).toFloat() }
        for (y in 0 until tile) {
            val wy = when {
                y < overlap -> ramp[y]
                y >= tile - overlap -> ramp[tile - 1 - y]
                else -> 1f
            }
            for (x in 0 until tile) {
                val wx = when {
                    x < overlap -> ramp[x]
                    x >= tile - overlap -> ramp[tile - 1 - x]
                    else -> 1f
                }
                w[y * tile + x] = wx * wy
            }
        }
        return w
    }

    /** 把页面某块裁出来；超出画布的部分用白色补齐到 tile×tile。 */
    private fun cropTile(page: Bitmap, x0: Int, y0: Int, tile: Int): Bitmap {
        val out = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val sx = max(0, x0)
        val sy = max(0, y0)
        val ex = min(page.width, x0 + tile)
        val ey = min(page.height, y0 + tile)
        if (ex > sx && ey > sy) {
            val src = Bitmap.createBitmap(page, sx, sy, ex - sx, ey - sy)
            c.drawBitmap(src, (sx - x0).toFloat(), (sy - y0).toFloat(), null)
        }
        return out
    }

    /**
     * 整页上色。
     * @param onTile 每完成一块回调 (已完成, 总数)
     */
    fun colorizePage(
        client: LocalDreamClient,
        page: Bitmap,
        tile: Int = 512,
        overlap: Int = 48,
        denoise: Float = DEFAULT_DENOISE,
        prompt: String = DEFAULT_PROMPT,
        negative: String = DEFAULT_NEGATIVE,
        steps: Int = 20,
        cfg: Float = 7f,
        scheduler: String = "dpm",
        baseSeed: Int? = null,
        onTile: ((Int, Int) -> Unit)? = null,
    ): Bitmap {
        val W = page.width
        val H = page.height
        val xs = axisPositions(W, tile, overlap)
        val ys = axisPositions(H, tile, overlap)
        val total = xs.size * ys.size

        // 浮点累加缓冲
        val acc = FloatArray(W * H * 3)
        val wacc = FloatArray(W * H)
        val weightMap = rampWeights(tile, overlap)

        var n = 0
        for (y in ys) {
            for (x in xs) {
                n++
                val patch = cropTile(page, x, y, tile)
                val colored = client.img2img(
                    src = patch,
                    prompt = prompt,
                    negative = negative,
                    denoise = denoise,
                    size = tile,
                    steps = steps,
                    cfg = cfg,
                    scheduler = scheduler,
                    seed = baseSeed?.let { it + n },
                )

                val cw = min(tile, W - x)
                val ch = min(tile, H - y)
                // 后端理论上返回 tile×tile；若尺寸不符则先缩放，避免 getPixels 越界
                val normalized = if (colored.width != tile || colored.height != tile) {
                    Bitmap.createScaledBitmap(colored, tile, tile, true)
                } else colored
                val cp = IntArray(tile * tile)
                normalized.getPixels(cp, 0, tile, 0, 0, tile, tile)

                for (yy in 0 until ch) {
                    val gy = y + yy
                    for (xx in 0 until cw) {
                        val gx = x + xx
                        val w = weightMap[yy * tile + xx]
                        val srcIdx = yy * tile + xx
                        val dstIdx = gy * W + gx
                        val px = cp[srcIdx]
                        acc[dstIdx * 3] += ((px shr 16) and 0xFF) * w
                        acc[dstIdx * 3 + 1] += ((px shr 8) and 0xFF) * w
                        acc[dstIdx * 3 + 2] += (px and 0xFF) * w
                        wacc[dstIdx] += w
                    }
                }
                onTile?.invoke(n, total)
            }
        }

        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(W * H)
        for (i in 0 until W * H) {
            val w = if (wacc[i] == 0f) 1f else wacc[i]
            val r = (acc[i * 3] / w).toInt().coerceIn(0, 255)
            val g = (acc[i * 3 + 1] / w).toInt().coerceIn(0, 255)
            val b = (acc[i * 3 + 2] / w).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(pixels, 0, W, 0, 0, W, H)
        return out
    }

    /**
     * 把原始线稿用正片叠底叠回彩色结果，保证线条不被 AI 改掉。
     *
     * @param strength  0=完全不叠，1=完全按原图线稿压暗
     * @param threshold 只对暗于该亮度的像素起作用（0~1），0.5 可保护网点
     */
    fun recomposeLines(
        colored: Bitmap,
        original: Bitmap,
        strength: Float = 1f,
        threshold: Float = 0f,
    ): Bitmap {
        val w = colored.width
        val h = colored.height
        val src = if (original.width != w || original.height != h) {
            Bitmap.createScaledBitmap(original, w, h, true)
        } else original

        val cp = IntArray(w * h).also { colored.getPixels(it, 0, w, 0, 0, w, h) }
        val op = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        val s = strength.coerceIn(0f, 1f)

        for (i in 0 until w * h) {
            val o = op[i]
            // 用 Rec.601 亮度，与 PIL 的 convert('L') 一致
            val gray = ((o shr 16 and 0xFF) * 0.299f +
                (o shr 8 and 0xFF) * 0.587f +
                (o and 0xFF) * 0.114f) / 255f
            var line = 1f - gray                       // 越暗 -> 越接近 1
            if (threshold > 0f) {
                line = ((line - threshold) / max(1e-6f, 1f - threshold)).coerceIn(0f, 1f)
            }
            val factor = 1f - s * line

            val c = cp[i]
            val r = (((c shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}
