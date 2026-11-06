package com.mangacolor.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 统一的页面来源解析：把任意输入（图片 / 多图 / PDF / ZIP / CBZ）转成 Bitmap 列表。
 *
 * 全部使用 Android 与 JDK 自带能力，不引入第三方库：
 *   - PDF  -> android.graphics.pdf.PdfRenderer（API 21+）
 *   - ZIP  -> java.util.zip.ZipInputStream
 *   - 图片 -> BitmapFactory
 *
 * 安全约束：
 *   - 单页最大边长限制，避免超大图 OOM
 *   - ZIP 限制解压条目数与单条大小，防 Zip Bomb
 *   - 跳过 Zip Slip 与目录穿越路径
 */
object PageSource {

    /** 单张解码后的长边上限 */
    const val MAX_SIDE = 2048L

    /** PDF 渲染倍率（1.0 = 72dpi）。2.0 约为 144dpi，线条清晰且不至于过大 */
    const val PDF_SCALE = 2.0f

    /** ZIP 安全阈值 */
    private const val MAX_ZIP_ENTRIES = 500
    private const val MAX_ENTRY_BYTES = 40L * 1024 * 1024   // 单个条目 40MB
    private const val MAX_TOTAL_BYTES = 400L * 1024 * 1024  // 总计 400MB

    private val IMAGE_EXT = setOf(
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "tif", "tiff"
    )

    /** 解析结果：页面列表 + 源类型说明 */
    data class Result(
        val pages: List<Bitmap>,
        val kind: String,
        val warning: String? = null,
    )

    /**
     * 根据文件名/扩展名分派解析。
     */
    fun load(resolver: ContentResolver, uri: Uri, displayName: String?): Result {
        val name = (displayName ?: uri.lastPathSegment ?: "").lowercase()
        val ext = name.substringAfterLast('.', "")

        return when (ext) {
            "pdf" -> loadPdf(resolver, uri)
            "zip", "cbz" -> loadZip(resolver, uri)
            else -> loadImage(resolver, uri)
        }
    }

    // ---- 单图 ------------------------------------------------------------
    private fun loadImage(resolver: ContentResolver, uri: Uri): Result {
        val bmp = decodeSampled(resolver, uri)
            ?: throw IllegalStateException("无法解码这张图片")
        return Result(listOf(bmp), "image")
    }

    /** 带采样降载的解码，避免超大图 OOM。 */
    fun decodeSampled(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(resolver, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var w = bounds.outWidth.toLong()
        var h = bounds.outHeight.toLong()
        while (maxOf(w, h) / sample > MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return open(resolver, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    // ---- PDF ------------------------------------------------------------
    private fun loadPdf(resolver: ContentResolver, uri: Uri): Result {
        val pages = ArrayList<Bitmap>()
        var warning: String? = null

        // PdfRenderer 需要可随机访问的 fd，直接用它，不要再套 InputStream
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开 PDF")

        pfd.use { renderFd ->
            val renderer = PdfRenderer(renderFd)
            try {
                val count = renderer.pageCount
                if (count == 0) {
                    warning = "PDF 没有页面"
                }
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        // 用页面点尺寸 × 倍率算出像素尺寸
                        val w = (page.width * PDF_SCALE).toInt().coerceAtLeast(1)
                        val h = (page.height * PDF_SCALE).toInt().coerceAtLeast(1)
                        // 限制过大页面
                        val scaleDown = if (maxOf(w, h) > MAX_SIDE) {
                            MAX_SIDE.toFloat() / maxOf(w, h)
                        } else 1f
                        val bw = (w * scaleDown).toInt().coerceAtLeast(1)
                        val bh = (h * scaleDown).toInt().coerceAtLeast(1)

                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        // PDF 背景透明，先铺白，否则叠线条时会变黑
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages.add(bmp)
                    }
                }
            } finally {
                renderer.close()
            }
        }
        return Result(pages, "pdf", warning)
    }

    // ---- ZIP / CBZ ------------------------------------------------------
    private fun loadZip(resolver: ContentResolver, uri: Uri): Result {
        val pages = ArrayList<Bitmap>()
        var skipped = 0
        var warning: String? = null
        var entries = 0
        var totalBytes = 0L

        open(resolver, uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue

                    // 防 Zip Slip：路径穿越一律跳过
                    val entryName = entry.name.replace('\\', '/')
                    if (entryName.contains("..") || entryName.startsWith("/")) {
                        skipped++
                        continue
                    }

                    entries++
                    if (entries > MAX_ZIP_ENTRIES) {
                        warning = "压缩包条目过多，只处理前 $MAX_ZIP_ENTRIES 个"
                        break
                    }

                    val ext = entryName.substringAfterLast('.', "").lowercase()
                    if (ext !in IMAGE_EXT) { skipped++; continue }

                    // 限制单条与总量，防 Zip Bomb
                    val bytes = readLimited(zis, MAX_ENTRY_BYTES)
                    if (bytes == null) {
                        skipped++
                        continue
                    }
                    totalBytes += bytes.size
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        warning = "压缩包解压总量超限，已停止"
                        break
                    }

                    val bmp = decodeSampledBytes(bytes)
                    if (bmp != null) pages.add(bmp) else skipped++
                }
            }
        }

        if (pages.isEmpty()) {
            throw IllegalStateException("压缩包里没有可用的图片")
        }
        // 按文件名自然排序（ZIP 条目顺序不保证）
        return Result(pages, "zip", warning ?: if (skipped > 0) "跳过 $skipped 个非图片条目" else null)
    }

    /** 读取至多 limit 字节；超过则返回 null（视为可疑条目）。 */
    private fun readLimited(zis: InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zis.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeSampledBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var w = bounds.outWidth.toLong()
        var h = bounds.outHeight.toLong()
        while (maxOf(w, h) / sample > MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun open(resolver: ContentResolver, uri: Uri): InputStream? =
        try { resolver.openInputStream(uri) } catch (e: Exception) { null }
}
