package com.mangacolor.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val pickRequest = 1001

    private lateinit var etApi: EditText
    private lateinit var ivPreview: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvQueue: TextView
    private lateinit var btnPick: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSave: Button
    private lateinit var progress: ProgressBar
    private lateinit var cbFast: CheckBox
    private lateinit var cbLimit: CheckBox

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

    /** 待处理页面（单图/多图/PDF/ZIP 统一成这个列表） */
    private val pages = ArrayList<Bitmap>()
    /** 已完成结果，key 为 pages 下标 */
    private val results = HashMap<Int, Bitmap>()

    private var currentIndex = 0
    private val stopFlag = AtomicBoolean(false)

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val loader = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApi = findViewById(R.id.etApi)
        ivPreview = findViewById(R.id.ivPreview)
        tvStatus = findViewById(R.id.tvStatus)
        tvQueue = findViewById(R.id.tvQueue)
        btnPick = findViewById(R.id.btnPick)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSave = findViewById(R.id.btnSave)
        progress = findViewById(R.id.progress)
        cbFast = findViewById(R.id.cbFast)
        cbLimit = findViewById(R.id.cbLimit)

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

        cbFast.setOnCheckedChangeListener { _, checked -> applyFastMode(checked) }
        cbLimit.isChecked = true

        btnPick.setOnClickListener { pickFiles() }
        btnStart.setOnClickListener { startBatch() }
        btnStop.setOnClickListener { stopFlag.set(true); tvStatus.text = "正在停止…" }
        btnSave.setOnClickListener { saveCurrent() }

        btnSave.isEnabled = false
        btnStop.isEnabled = false
        progress.visibility = View.GONE
        updateQueueLabel()
    }

    // ---- 参数控件 --------------------------------------------------------
    private fun setupSeekBars() {
        sbDenoise.max = 60                       // 0.40 ~ 1.00
        sbDenoise.progress = 40                  // 0.80（官方推荐 0.75-0.85）
        sbTile.max = 8                           // 256 ~ 768
        sbTile.progress = 4                      // 512
        sbOverlap.max = 6                        // 0 ~ 192
        sbOverlap.progress = 1                   // 48（比 96 更快）
        sbSteps.max = 36                         // 4 ~ 40
        sbSteps.progress = 16                    // 20
        sbCfg.max = 110                          // 1.0 ~ 12.0
        sbCfg.progress = 60                      // 7.0
        sbLine.max = 100
        sbLine.progress = 100
        sbThreshold.max = 80
        sbThreshold.progress = 0

        val render: () -> Unit = {
            tvDenoise.text = "上色强度 %.2f".format(denoise())
            tvTile.text = "切块 ${tile()} px"
            tvOverlap.text = "重叠 ${overlap()} px"
            tvSteps.text = "步数 ${steps()}"
            tvCfg.text = "CFG %.1f".format(cfg())
            tvLine.text = "线条回贴 ${sbLine.progress}%"
            tvThreshold.text = "网点保护 ${sbThreshold.progress}%"
        }
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = render()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        listOf(sbDenoise, sbTile, sbOverlap, sbSteps, sbCfg, sbLine, sbThreshold)
            .forEach { it.setOnSeekBarChangeListener(l) }
        render()
    }

    /** 快模式：DMD2/LCM 官方推荐参数（8 步 / CFG 1 / LCM） */
    private fun applyFastMode(on: Boolean) {
        if (on) {
            sbSteps.progress = 4        // 8 步
            sbCfg.progress = 0          // CFG 1.0 —— 必须精确为 1 才会跳过无条件分支
            tvStatus.text = "快模式已开：8 步 / CFG 1（需配合 DMD2/LCM 模型）"
        } else {
            sbSteps.progress = 16       // 20 步
            sbCfg.progress = 60         // CFG 7.0
            tvStatus.text = "快模式已关：20 步 / CFG 7"
        }
    }

    private fun denoise() = (40 + sbDenoise.progress) / 100f
    private fun tile() = 256 + sbTile.progress * 64
    private fun overlap() = sbOverlap.progress * 32
    private fun steps() = 4 + sbSteps.progress
    private fun cfg() = 1f + sbCfg.progress / 10f
    private fun lineStrength() = sbLine.progress / 100f
    private fun lineThreshold() = sbThreshold.progress / 100f

    // ---- 导入 ------------------------------------------------------------
    private fun pickFiles() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(i, "选择漫画图 / PDF / ZIP"), pickRequest)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || requestCode != pickRequest) return

        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data?.data?.let { uris.add(it) }

        if (uris.isEmpty()) return
        loadFiles(uris)
    }

    private fun loadFiles(uris: List<Uri>) {
        btnPick.isEnabled = false
        tvStatus.text = "正在解析 ${uris.size} 个文件…"
        loader.execute {
            val loaded = ArrayList<Bitmap>()
            val notes = ArrayList<String>()
            for (u in uris) {
                val name = queryName(u)
                try {
                    val r = PageSource.load(contentResolver, u, name)
                    loaded.addAll(r.pages)
                    val label = when (r.kind) {
                        "pdf" -> "PDF ${r.pages.size} 页"
                        "zip" -> "压缩包 ${r.pages.size} 张"
                        else -> "图片"
                    }
                    notes.add(label + (r.warning?.let { "（$it）" } ?: ""))
                } catch (e: Exception) {
                    notes.add("${name ?: "文件"} 解析失败: ${e.message}")
                }
            }
            ui.post {
                btnPick.isEnabled = true
                if (loaded.isEmpty()) {
                    tvStatus.text = "没有解析出可用页面：${notes.joinToString("; ")}"
                    return@post
                }
                pages.clear()
                pages.addAll(loaded)
                results.clear()
                currentIndex = 0
                ivPreview.setImageBitmap(pages[0])
                btnSave.isEnabled = false
                tvStatus.text = "已载入 ${pages.size} 页 · ${notes.joinToString("; ")}"
                updateQueueLabel()
            }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun showCurrent() {
        if (pages.isEmpty()) {
            ivPreview.setImageBitmap(null)
            return
        }
        val i = currentIndex.coerceIn(0, pages.size - 1)
        ivPreview.setImageBitmap(results[i] ?: pages[i])
        btnSave.isEnabled = results.containsKey(i)
    }

    private fun updateQueueLabel() {
        tvQueue.text = if (pages.isEmpty()) "未载入"
        else "共 ${pages.size} 页 · 已完成 ${results.size} 页 · 当前第 ${currentIndex + 1} 页"
    }

    // ---- 批量上色 --------------------------------------------------------
    private fun startBatch() {
        if (pages.isEmpty()) {
            toast("请先导入漫画图 / PDF / ZIP")
            return
        }
        val api = etApi.text.toString().trim().ifEmpty { "http://127.0.0.1:8081" }

        val d = denoise(); val t = tile(); val o = overlap()
        val st = steps(); val c = cfg()
        val ls = lineStrength(); val lt = lineThreshold()
        val limitSide = cbLimit.isChecked

        stopFlag.set(false)
        btnStart.isEnabled = false
        btnPick.isEnabled = false
        btnStop.isEnabled = true
        progress.visibility = View.VISIBLE
        btnSave.isEnabled = false

        val snapshot = ArrayList(pages)

        worker.execute {
            val client = LocalDreamClient(api)
            if (!client.ping()) {
                ui.post {
                    tvStatus.text = "连不上 local-dream。请先打开它并加载一个模型。"
                    endBatchUi()
                }
                return@execute
            }

            for (i in snapshot.indices) {
                if (stopFlag.get()) break
                ui.post {
                    currentIndex = i
                    tvStatus.text = "第 ${i + 1}/${snapshot.size} 页 · 准备中"
                    ivPreview.setImageBitmap(snapshot[i])
                    updateQueueLabel()
                }
                try {
                    val original = snapshot[i]
                    // 限制长边可大幅减少切块数（1200x1800 从 15 块降到 6 块）
                    val src = if (limitSide) scaleDown(original, 1024) else original

                    val colored = Colorizer.colorizePage(
                        client = client,
                        page = src,
                        tile = t,
                        overlap = o,
                        denoise = d,
                        steps = st,
                        cfg = c,
                    ) { done, total ->
                        ui.post {
                            progress.max = total
                            progress.progress = done
                            tvStatus.text = "第 ${i + 1}/${snapshot.size} 页 · $done/$total 块"
                        }
                    }

                    val withLines =
                        if (ls > 0f) Colorizer.recomposeLines(colored, src, ls, lt) else colored

                    // 限分辨率时放大回原尺寸
                    val outBmp = if (withLines.width != original.width ||
                        withLines.height != original.height) {
                        Bitmap.createScaledBitmap(withLines, original.width, original.height, true)
                    } else withLines

                    ui.post {
                        results[i] = outBmp
                        if (currentIndex == i) ivPreview.setImageBitmap(outBmp)
                        btnSave.isEnabled = true
                        updateQueueLabel()
                    }
                } catch (e: Exception) {
                    ui.post { tvStatus.text = "第 ${i + 1} 页失败: ${e.message}" }
                }
            }

            ui.post {
                val stopped = stopFlag.get()
                tvStatus.text = if (stopped) "已停止 · 完成 ${results.size}/${snapshot.size} 页"
                else "全部完成 · ${results.size}/${snapshot.size} 页"
                progress.visibility = View.GONE
                endBatchUi()
                updateQueueLabel()
                showCurrent()
            }
        }
    }

    private fun endBatchUi() {
        btnStart.isEnabled = true
        btnPick.isEnabled = true
        btnStop.isEnabled = false
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * s).toInt().coerceAtLeast(1),
            (src.height * s).toInt().coerceAtLeast(1),
            true,
        )
    }

    // ---- 保存 ------------------------------------------------------------
    private fun saveCurrent() {
        val bmp = results[currentIndex]
        if (bmp == null) {
            toast("当前页还没有结果")
            return
        }
        val name = "mangacolor_${System.currentTimeMillis()}_p${currentIndex + 1}.png"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MangaColor")
        }
        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
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
        stopFlag.set(true)
        worker.shutdownNow()
        loader.shutdownNow()
    }
}
