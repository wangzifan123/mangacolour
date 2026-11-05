package com.mangacolor.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val pickRequest = 1001

    private lateinit var etApi: EditText
    private lateinit var ivPreview: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnPick: Button
    private lateinit var btnStart: Button
    private lateinit var btnSave: Button
    private lateinit var progress: ProgressBar

    private lateinit var sbDenoise: SeekBar
    private lateinit var sbTile: SeekBar
    private lateinit var sbOverlap: SeekBar
    private lateinit var sbSteps: SeekBar
    private lateinit var sbCfg: SeekBar
    private lateinit var sbLine: SeekBar
    private lateinit var sbThreshold: SeekBar

    private lateinit var tvDenoise: TextView
    private lateinit var tvTile: TextView
    private lateinit var tvOverlap: TextView
    private lateinit var tvSteps: TextView
    private lateinit var tvCfg: TextView
    private lateinit var tvLine: TextView
    private lateinit var tvThreshold: TextView

    private var source: Bitmap? = null
    private var result: Bitmap? = null

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApi = findViewById(R.id.etApi)
        ivPreview = findViewById(R.id.ivPreview)
        tvStatus = findViewById(R.id.tvStatus)
        btnPick = findViewById(R.id.btnPick)
        btnStart = findViewById(R.id.btnStart)
        btnSave = findViewById(R.id.btnSave)
        progress = findViewById(R.id.progress)

        sbDenoise = findViewById(R.id.sbDenoise)
        sbTile = findViewById(R.id.sbTile)
        sbOverlap = findViewById(R.id.sbOverlap)
        sbSteps = findViewById(R.id.sbSteps)
        sbCfg = findViewById(R.id.sbCfg)
        sbLine = findViewById(R.id.sbLine)
        sbThreshold = findViewById(R.id.sbThreshold)

        tvDenoise = findViewById(R.id.tvDenoise)
        tvTile = findViewById(R.id.tvTile)
        tvOverlap = findViewById(R.id.tvOverlap)
        tvSteps = findViewById(R.id.tvSteps)
        tvCfg = findViewById(R.id.tvCfg)
        tvLine = findViewById(R.id.tvLine)
        tvThreshold = findViewById(R.id.tvThreshold)

        etApi.setText("http://127.0.0.1:8081")
        setupSeekBars()

        btnPick.setOnClickListener { pickImage() }
        btnStart.setOnClickListener { startColorize() }
        btnSave.setOnClickListener { saveResult() }
        btnSave.isEnabled = false
        progress.visibility = View.GONE
    }

    private fun setupSeekBars() {
        // 上色强度 denoise: 20~70 -> 0.20~0.70
        sbDenoise.max = 50
        sbDenoise.progress = 25                       // 0.45
        // 切块 tile: 256~768，步长 64
        sbTile.max = 8
        sbTile.progress = 4                           // 512
        // 重叠 overlap: 0~192，步长 32
        sbOverlap.max = 6
        sbOverlap.progress = 3                        // 96
        // 步数 4~40
        sbSteps.max = 36
        sbSteps.progress = 16                         // 20
        // CFG 1.0~12.0
        sbCfg.max = 110
        sbCfg.progress = 60                           // 7.0
        // 线条回贴 0~100%
        sbLine.max = 100
        sbLine.progress = 100
        // 网点保护 0~80%
        sbThreshold.max = 80
        sbThreshold.progress = 0

        val render = {
            tvDenoise.text = "上色强度 %.2f".format(denoise())
            tvTile.text = "切块 ${tile()} px"
            tvOverlap.text = "重叠 ${overlap()} px"
            tvSteps.text = "步数 ${steps()}"
            tvCfg.text = "CFG %.1f".format(cfg())
            tvLine.text = "线条回贴 ${sbLine.progress}%"
            tvThreshold.text = "网点保护 ${sbThreshold.progress}%"
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = render()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        listOf(sbDenoise, sbTile, sbOverlap, sbSteps, sbCfg, sbLine, sbThreshold)
            .forEach { it.setOnSeekBarChangeListener(listener) }
        render()
    }

    private fun denoise() = (20 + sbDenoise.progress) / 100f
    private fun tile() = 256 + sbTile.progress * 64
    private fun overlap() = sbOverlap.progress * 32
    private fun steps() = 4 + sbSteps.progress
    private fun cfg() = 1f + sbCfg.progress / 10f
    private fun lineStrength() = sbLine.progress / 100f
    private fun lineThreshold() = sbThreshold.progress / 100f

    // ---- 选图 ------------------------------------------------------------
    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        startActivityForResult(i, pickRequest)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            pickRequest -> {
                data?.data?.let { uri ->
                    try {
                        val bmp = decodeBitmap(uri)
                        if (bmp == null) {
                            toast("无法读取这张图")
                            return
                        }
                        source = bmp
                        result = null
                        btnSave.isEnabled = false
                        ivPreview.setImageBitmap(bmp)
                        tvStatus.text = "已载入 ${bmp.width}×${bmp.height}"
                    } catch (e: Exception) {
                        toast("读取失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        // 先读尺寸，避免超大图直接 OOM
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val maxSide = 2048
        var scale = 1
        while (opts.outWidth / scale > maxSide || opts.outHeight / scale > maxSide) scale *= 2

        val opts2 = BitmapFactory.Options().apply {
            inSampleSize = scale
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) }
    }

    private fun openStream(uri: Uri): InputStream? =
        try { contentResolver.openInputStream(uri) } catch (e: Exception) { null }

    // ---- 上色 ------------------------------------------------------------
    private fun startColorize() {
        val src = source
        if (src == null) {
            toast("请先选一张漫画图")
            return
        }
        val api = etApi.text.toString().trim().ifEmpty { "http://127.0.0.1:8081" }

        btnStart.isEnabled = false
        btnPick.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        tvStatus.text = "检查 local-dream 连接…"

        val d = denoise(); val t = tile(); val o = overlap()
        val st = steps(); val c = cfg()
        val ls = lineStrength(); val lt = lineThreshold()

        worker.execute {
            try {
                val client = LocalDreamClient(api)
                if (!client.ping()) {
                    ui.post {
                        tvStatus.text = "连不上 local-dream。请先打开它并在界面上加载一个模型。"
                        resetButtons()
                    }
                    return@execute
                }
                ui.post { tvStatus.text = "开始切块上色…" }

                val colored = Colorizer.colorizePage(
                    client = client,
                    page = src,
                    tile = t,
                    overlap = o,
                    denoise = d,
                    steps = st,
                    cfg = c,
                    baseSeed = null,
                ) { done, total ->
                    ui.post {
                        progress.max = total
                        progress.progress = done
                        tvStatus.text = "上色中 $done/$total 块"
                    }
                }

                val outBmp = if (ls > 0f) {
                    ui.post { tvStatus.text = "回贴线条…" }
                    Colorizer.recomposeLines(colored, src, ls, lt)
                } else colored

                ui.post {
                    result = outBmp
                    ivPreview.setImageBitmap(outBmp)
                    tvStatus.text = "完成 ${outBmp.width}×${outBmp.height}，可保存"
                    btnSave.isEnabled = true
                    progress.visibility = View.GONE
                    resetButtons()
                }
            } catch (e: Exception) {
                ui.post {
                    tvStatus.text = "失败: ${e.message}"
                    progress.visibility = View.GONE
                    resetButtons()
                }
            }
        }
    }

    private fun resetButtons() {
        btnStart.isEnabled = true
        btnPick.isEnabled = true
    }

    // ---- 保存 ------------------------------------------------------------
    private fun saveResult() {
        val bmp = result
        if (bmp == null) {
            toast("还没有结果")
            return
        }
        val name = "mangacolor_${System.currentTimeMillis()}.png"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MangaColor")
        }
        try {
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri == null) {
                toast("创建文件失败")
                return
            }
            contentResolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            toast("已保存到 相册/MangaColor")
        } catch (e: Exception) {
            toast("保存失败: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }
}
