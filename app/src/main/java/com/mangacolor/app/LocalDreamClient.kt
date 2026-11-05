package com.mangacolor.app

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import android.util.Base64

/**
 * local-dream 后端（127.0.0.1:8081）客户端。
 *
 * 严格复刻官方协议（与 mangacolor.py 同一套已验证实现）：
 *   POST /tokenize -> {"count":Int,"max_length":77}
 *   POST /generate -> text/event-stream
 *        event: progress / complete / error
 *
 * 协议不对称（关键）：
 *   请求 image  = base64(PNG/JPG 字节)
 *   响应 image  = base64(raw RGB 像素)，需按 width/height/channels 自行组装 Bitmap
 */
class LocalDreamClient(private val baseUrl: String = "http://127.0.0.1:8081") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 后端是否在线。local-dream 未加载模型时这里会失败。 */
    fun ping(): Boolean = try {
        val body = JSONObject().put("prompt", "test")
            .toString().toRequestBody(JSON)
        val req = Request.Builder().url("$baseUrl/tokenize").post(body).build()
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    /**
     * img2img 上色。
     * @return 与输入同尺寸的上色结果
     */
    fun img2img(
        src: Bitmap,
        prompt: String,
        negative: String,
        denoise: Float,
        size: Int,
        steps: Int,
        cfg: Float,
        scheduler: String,
        seed: Int?,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Bitmap {
        val png = ByteArrayOutputStream().also {
            src.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val b64 = Base64.encodeToString(png, Base64.NO_WRAP)

        val json = JSONObject().apply {
            put("prompt", prompt)
            put("negative_prompt", negative)
            put("steps", steps)
            put("cfg", cfg.toDouble())
            put("scheduler", scheduler)
            put("size", size)
            put("denoise_strength", denoise.toDouble())
            put("image", b64)
            seed?.let { put("seed", it) }
        }

        val req = Request.Builder()
            .url("$baseUrl/generate")
            .post(json.toString().toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw LocalDreamException("HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
            }
            val source = resp.body?.source() ?: throw LocalDreamException("响应为空")

            var event: String? = null
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        when (event) {
                            "error" -> {
                                val msg = try {
                                    JSONObject(payload).optString("message", payload)
                                } catch (e: Exception) {
                                    payload
                                }
                                throw LocalDreamException("后端报错: $msg")
                            }
                            "complete" -> return decodeComplete(payload, size)
                            "progress" -> {
                                try {
                                    val o = JSONObject(payload)
                                    onProgress?.invoke(
                                        o.optInt("step", 0),
                                        o.optInt("total_steps", steps)
                                    )
                                } catch (_: Exception) { /* 进度解析失败不影响主流程 */ }
                            }
                        }
                    }
                }
            }
        }
        throw LocalDreamException("流结束但没有收到 complete 事件")
    }

    /** 把 complete 事件里的 base64 raw RGB 组装成 Bitmap。 */
    private fun decodeComplete(data: String, fallback: Int): Bitmap {
        val o = JSONObject(data)
        val raw = Base64.decode(o.getString("image"), Base64.DEFAULT)
        val w = o.optInt("width", fallback)
        val h = o.optInt("height", fallback)
        val c = o.optInt("channels", 3)
        if (c != 3) throw LocalDreamException("暂不支持的通道数: $c")
        val expect = w * h * c
        if (raw.size != expect) {
            throw LocalDreamException("像素数不匹配: 收到 ${raw.size}，期望 $expect (${w}x${h}x$c)")
        }
        // raw RGB -> ARGB int 数组
        val pixels = IntArray(w * h)
        var i = 0
        for (p in 0 until w * h) {
            val r = raw[i].toInt() and 0xFF
            val g = raw[i + 1].toInt() and 0xFF
            val b = raw[i + 2].toInt() and 0xFF
            pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            i += 3
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class LocalDreamException(message: String) : Exception(message)
