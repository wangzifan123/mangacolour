package com.mangacolor.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GAN 上色界面（hint 涂色路线）。
 *
 * 与 MainActivity（SD 路线）完全独立，复用 PageSource 做导入、复用同样的 MediaStore 保存方式。
 *
 * hint 交互模型：
 *   用户在缩略画布上涂色 -> HintCanvas 把笔迹按**原图坐标**保存
 *   -> buildHint() 生成 hintRGB + hintMask(0/1)
 *   -> GanColorizer.colorize(src, hintRGB, hintMask) 内部转 ch1-3=((c/255)-0.5)/0.5, ch4=mask
 *   -> GanPostProcess.process 做 L 保真/线条回贴
 *
 * 依赖：GanColorizer（task-1）负责推理，GanPostProcess（task-2）负责后处理。
 * 两者都是 Kotlin object，直接静态调用。
 */
class GanActivity : AppCompatActivity() {

    private val pickRequest = 2001

    private lateinit var canvas: HintCanvas
    private lateinit var tvTitle: TextView
    private lateinit var tvQueue: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPage: TextView
    private lateinit var tvBrush: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var colorRow: LinearLayout
    private lateinit var sbBrush: SeekBar
    private lateinit var cbHint: CheckBox
    private lateinit var btnPick: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnCompare: Button
    private lateinit var btnCustomColor: Button
    private lateinit var btnUndo: Button
    private lateinit var btnClear: Button
    private lateinit var btnColorize: Button
    private lateinit var btnBatch: Button
    private lateinit var btnStop: Button
    private lateinit var btnSave: Button

    /** 待处理页面（单图 / 多图 / PDF / ZIP 统一成这个列表） */
    private val pages = ArrayList<Bitmap>()
    /** 已完成结果，key 为 pages 下标 */
    private val results = HashMap<Int, Bitmap>()
    private var currentIndex = 0

    private val stopFlag = AtomicBoolean(false)
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val loader = Executors.newSingleThreadExecutor()

    /** 预设色板：漫画常用肤色 / 发色 / 服饰 / 环境色 */
    private val presets = intArrayOf(
        0xFFF4845F.toInt(), // 肤色
        0xFFE8B04B.toInt(), // 金发
        0xFF4A6FA5.toInt(), // 深蓝
        0xFF6FA8DC.toInt(), // 浅蓝
        0xFFD45D79.toInt(), // 玫红
        0xFF7BB661.toInt(), // 草绿
        0xFF9B72CF.toInt(), // 紫
        0xFFB0B7C3.toInt(), // 灰
        0xFF2B2B2B.toInt(), // 黑
        0xFFF2F2F2.toInt(), // 白
    )
    private var brushColor = presets[0]
    private var colorChips = ArrayList<View>()

    /** 推理进行中：用于屏蔽编辑类按钮，避免中途改画布 */
    private var busy = false

    /** 最近一次上色失败原因（用于界面提示） */
    private var lastGanError: String? = null

    // =====================================================================
    // 生命周期
    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gan)

        canvas = findViewById(R.id.hintCanvas)
        tvTitle = findViewById(R.id.tvGanTitle)
        tvQueue = findViewById(R.id.tvGanQueue)
        tvStatus = findViewById(R.id.tvGanStatus)
        tvPage = findViewById(R.id.tvGanPage)
        tvBrush = findViewById(R.id.tvBrush)
        tvEmpty = findViewById(R.id.tvGanEmpty)
        progress = findViewById(R.id.progressGan)
        colorRow = findViewById(R.id.colorRow)
        sbBrush = findViewById(R.id.sbBrush)
        cbHint = findViewById(R.id.cbGanHint)
        btnPick = findViewById(R.id.btnGanPick)
        btnPrev = findViewById(R.id.btnGanPrev)
        btnNext = findViewById(R.id.btnGanNext)
        btnCompare = findViewById(R.id.btnGanCompare)
        btnCustomColor = findViewById(R.id.btnGanCustomColor)
        btnUndo = findViewById(R.id.btnGanUndo)
        btnClear = findViewById(R.id.btnGanClear)
        btnColorize = findViewById(R.id.btnGanColorize)
        btnBatch = findViewById(R.id.btnGanBatch)
        btnStop = findViewById(R.id.btnGanStop)
        btnSave = findViewById(R.id.btnGanSave)

        setupColorPicker()
        setupBrush()

        btnPick.setOnClickListener { pickFiles() }
        btnPrev.setOnClickListener { goTo(currentIndex - 1) }
        btnNext.setOnClickListener { goTo(currentIndex + 1) }
        btnCompare.setOnClickListener { toggleCompare() }
        btnCustomColor.setOnClickListener { showCustomColorDialog() }
        btnUndo.setOnClickListener {
            if (!canvas.undo()) toast("没有可撤销的涂色")
        }
        btnClear.setOnClickListener { canvas.clearHints() }
        btnColorize.setOnClickListener { colorizeCurrent() }
        btnBatch.setOnClickListener { startBatch() }
        btnStop.setOnClickListener { stopFlag.set(true); setStatus("正在停止…") }
        btnSave.setOnClickListener { saveCurrent() }

        cbHint.setOnCheckedChangeListener { _, checked ->
            canvas.hintVisible = checked
            canvas.invalidate()      // 普通属性赋值不会自动重绘，必须显式刷新
        }

        canvas.onStrokesChanged = { refreshEditButtons() }

        setBusy(false)
        refreshEditButtons()
        updateLabels()

        // 模型初始化放后台：init() 要把 asset 里的 58.8MB 模型拷出来并建 session，
        // 在主线程会卡住界面，且首次耗时明显。
        worker.execute {
            try {
                GanColorizer.init(applicationContext)
                ui.post { if (!isFinishing) setStatus("模型就绪，可以开始上色") }
            } catch (e: Throwable) {
                lastGanError = e.message ?: e.javaClass.simpleName
                ui.post {
                    if (!isFinishing) {
                        setStatus("模型加载失败：${lastGanError}")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFlag.set(true)
        worker.shutdownNow()
        loader.shutdownNow()
    }

    // =====================================================================
    // 颜色选择器
    // =====================================================================
    private fun setupColorPicker() {
        colorRow.removeAllViews()
        colorChips.clear()
        val size = dp(34)
        val gap = dp(6)
        for (c in presets) {
            val chip = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = gap
            chip.layoutParams = lp
            chip.background = chipDrawable(c, false)
            chip.setOnClickListener { selectColor(c) }
            colorRow.addView(chip)
            colorChips.add(chip)
        }
        selectColor(brushColor)
    }

    private fun chipDrawable(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) {
                setStroke(dp(3), Color.parseColor("#FF8C42"))
            } else {
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
        }

    private fun selectColor(color: Int) {
        brushColor = color
        canvas.brushColor = color
        presets.forEachIndexed { i, c ->
            if (i < colorChips.size) {
                colorChips[i].background = chipDrawable(c, c == color)
            }
        }
        setStatus("画笔颜色 #%06X".format(color and 0xFFFFFF))
    }

    /** 自定义颜色：用系统取色器不可靠（各家 ROM 实现不一），这里用 HSV 精简对话框 */
    private fun showCustomColorDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }
        val preview = View(this)
        preview.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        )
        container.addView(preview)

        val r = SeekBar(this).apply { max = 255 }
        val g = SeekBar(this).apply { max = 255 }
        val b = SeekBar(this).apply { max = 255 }
        r.progress = Color.red(brushColor)
        g.progress = Color.green(brushColor)
        b.progress = Color.blue(brushColor)

        val update: () -> Unit = {
            preview.background = GradientDrawable().apply {
                setColor(Color.rgb(r.progress, g.progress, b.progress))
                cornerRadius = dp(8).toFloat()
            }
        }
        update()

        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                update()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        listOf(r, g, b).forEach { it.setOnSeekBarChangeListener(l) }

        listOf("R" to r, "G" to g, "B" to b).forEach { (name, bar) ->
            container.addView(TextView(this).apply {
                text = name
                textSize = 12f
            })
            container.addView(bar)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("自定义颜色")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                selectColor(Color.rgb(r.progress, g.progress, b.progress))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // =====================================================================
    // 画笔
    // =====================================================================
    private fun setupBrush() {
        sbBrush.max = 96                      // 4 ~ 100 dp
        sbBrush.progress = 20                 // 24dp
        sbBrush.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = applyBrush()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        applyBrush()
    }

    private fun applyBrush() {
        val radiusDp = 4 + sbBrush.progress          // 4..100
        tvBrush.text = "画笔 $radiusDp"
        canvas.brushRadiusPx = dp(radiusDp).toFloat()
    }

    // =====================================================================
    // 导入
    // =====================================================================
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
        setStatus("正在解析 ${uris.size} 个文件…")
        progress.visibility = View.VISIBLE

        loader.execute {
            val loaded = ArrayList<Bitmap>()
            val notes = ArrayList<String>()
            for (u in uris) {
                val name = queryName(u)
                try {
                    // 直接复用 PageSource：PDF / ZIP / 多选统一成 Bitmap 列表
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
                progress.visibility = View.GONE
                if (loaded.isEmpty()) {
                    setStatus("没有解析出可用页面：${notes.joinToString("; ")}")
                    return@post
                }
                pages.clear()
                pages.addAll(loaded)
                results.clear()
                currentIndex = 0
                tvEmpty.visibility = View.GONE
                canvas.setPage(pages[0])
                canvas.setResult(null)
                setStatus("已载入 ${pages.size} 页 · ${notes.joinToString("; ")}")
                updateLabels()
                refreshEditButtons()
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

    // =====================================================================
    // 翻页 / 对比
    // =====================================================================
    private fun goTo(index: Int) {
        if (pages.isEmpty()) return
        val i = index.coerceIn(0, pages.size - 1)
        if (i == currentIndex) return
        currentIndex = i
        canvas.setPage(pages[i])
        canvas.setResult(results[i])
        // 有结果时默认看结果、关掉覆盖层；没结果时回线稿、开覆盖层。
        // 注意顺序：先设 hintVisible 再设 isChecked，让监听器做最后一次刷新。
        val viewing = results[i] != null
        canvas.hintVisible = !viewing
        cbHint.isChecked = !viewing
        btnCompare.text = if (viewing) "看线稿" else "看结果"
        updateLabels()
        refreshEditButtons()
    }

    /** 在「线稿+涂色」和「上色结果」之间切换 */
    private fun toggleCompare() {
        if (pages.isEmpty()) return
        val result = results[currentIndex]
        if (result == null) {
            toast("本页还没有上色结果")
            return
        }
        if (canvas.hasResult()) {
            canvas.setResult(null)
            // 回到线稿时必须恢复覆盖层，否则用户看不到自己涂过什么
            canvas.hintVisible = cbHint.isChecked
            canvas.invalidate()
            btnCompare.text = "看结果"
            setStatus("正在查看线稿 + 涂色提示")
        } else {
            canvas.setResult(result)
            btnCompare.text = "看线稿"
            setStatus("正在查看上色结果")
        }
        refreshEditButtons()
    }

    private fun updateLabels() {
        tvQueue.text = if (pages.isEmpty()) "未载入"
        else "共 ${pages.size} 页 · 已完成 ${results.size} 页"
        tvPage.text = if (pages.isEmpty()) "- / -" else "${currentIndex + 1} / ${pages.size}"
        // 注意：翻页按钮的可用状态统一由 refreshEditButtons 决定（要叠加 busy 判断），
        // 这里不再单独设置，否则推理中会被改回可用。
    }

    private fun refreshEditButtons() {
        val hasPage = pages.isNotEmpty()
        val viewingResult = canvas.hasResult()
        val strokes = canvas.hintStrokeCount()
        // 推理中不允许改画布/切页，避免中途改 hint
        btnUndo.isEnabled = hasPage && strokes > 0 && !viewingResult && !busy
        btnClear.isEnabled = hasPage && strokes > 0 && !viewingResult && !busy
        btnColorize.isEnabled = hasPage && !viewingResult && !busy
        btnBatch.isEnabled = hasPage && !viewingResult && !busy
        btnPrev.isEnabled = !busy && currentIndex > 0
        btnNext.isEnabled = !busy && currentIndex < pages.size - 1
        btnSave.isEnabled = results.containsKey(currentIndex) && !busy
        btnCompare.isEnabled = results.containsKey(currentIndex) && !busy
    }

    // =====================================================================
    // 上色
    // =====================================================================
    private fun colorizeCurrent() {
        if (pages.isEmpty()) {
            toast("请先导入页面")
            return
        }
        val src = pages[currentIndex]
        val index = currentIndex
        val wantHint = cbHint.isChecked

        stopFlag.set(false)
        setBusy(true)
        setStatus("正在上色第 ${currentIndex + 1} 页…")

        worker.execute {
            try {
                // buildHint 要逐行扫描整张图并建位图（2048x2048 约 20MB、1-2 秒），
                // 必须放后台，否则大图会让界面卡住。
                var rgb: Bitmap? = null
                var mask: ByteArray? = null
                if (wantHint) {
                    val pair = canvas.buildHint()
                    rgb = pair.first
                    mask = pair.second
                }
                val out = runGanPipeline(
                    src = src,
                    hintRgb = if (rgb != null && mask != null) rgb else null,
                    hintMask = if (rgb != null && mask != null) mask else null,
                )
                ui.post {
                    if (out != null) {
                        results[index] = out
                        if (currentIndex == index) {
                            canvas.setResult(out)
                            cbHint.isChecked = false
                            btnCompare.text = "看线稿"
                        }
                        setStatus("第 ${index + 1} 页完成")
                    } else {
                        setStatus("上色失败：${lastGanError ?: "未知错误"}")
                    }
                    setBusy(false)
                    updateLabels()
                    refreshEditButtons()
                }
            } catch (e: Exception) {
                ui.post {
                    setStatus("第 ${index + 1} 页失败: ${e.message}")
                    setBusy(false)
                    refreshEditButtons()
                }
            }
        }
    }

    /**
     * 批量队列：逐页上色，带进度与停止。
     *
     * 语义（已与 lead 确认）：批量 = **逐页不带 hint** 的全自动上色。
     * 理由：每页画面内容不同，把第 1 页的涂色套到第 2 页没有意义。
     * 想要精细控制颜色，请在单页上涂色后用「上色本页」。
     * 「逐页各自涂色」属于 v2，需要改成"跑完一页停下等用户涂"的交互。
     */
    private fun startBatch() {
        if (pages.isEmpty()) {
            toast("请先导入页面")
            return
        }
        if (canvas.hintStrokeCount() > 0) {
            toast("批量模式为全自动，不使用当前涂色")
        }

        stopFlag.set(false)
        setBusy(true)
        val snapshot = ArrayList(pages)
        val startIndex = currentIndex

        worker.execute {
            var ok = 0
            var fail = 0
            for (i in startIndex until snapshot.size) {
                if (stopFlag.get()) break
                ui.post {
                    setStatus("批量 ${i + 1}/${snapshot.size} · 处理中")
                    tvPage.text = "${i + 1} / ${snapshot.size}"
                }
                try {
                    val out = runGanPipeline(src = snapshot[i], hintRgb = null, hintMask = null)
                    if (out != null) {
                        ok++
                        ui.post {
                            results[i] = out
                            if (currentIndex == i) {
                                canvas.setResult(out)
                                cbHint.isChecked = false
                                btnCompare.text = "看线稿"
                            }
                            updateLabels()
                            refreshEditButtons()
                        }
                    } else {
                        fail++
                        ui.post { setStatus("第 ${i + 1} 页跳过：${lastGanError ?: "未知错误"}") }
                    }
                } catch (e: Exception) {
                    fail++
                    ui.post { setStatus("第 ${i + 1} 页失败: ${e.message}") }
                }
            }
            ui.post {
                val stopped = stopFlag.get()
                setStatus(
                    when {
                        stopped -> "已停止 · 成功 $ok 页，失败 $fail 页"
                        else -> "批量完成 · 成功 $ok 页，失败 $fail 页"
                    }
                )
                setBusy(false)
                updateLabels()
                refreshEditButtons()
            }
        }
    }

    /**
     * 单页完整管线：GAN 上色 -> 后处理。
     * 供单页与批量共用。
     *
     * 注意：必须在后台线程调用。GanColorizer.colorize() 检测到主线程会直接抛异常
     * （推理在 ARM CPU 上要几十秒），这里由 worker 线程保证。
     */
    private fun runGanPipeline(src: Bitmap, hintRgb: Bitmap?, hintMask: ByteArray?): Bitmap? {
        return try {
            // 防御：模型可能还在后台初始化。init() 自带幂等检查，这里再调一次兜底
            // （已经 ready 会立即返回，不会重复加载）。
            if (!GanColorizer.isReady) {
                GanColorizer.init(applicationContext)
            }
            // hint 必须成对出现：GanColorizer 对"只给 rgb 不给 mask"会抛异常
            val colored = if (hintRgb != null && hintMask != null) {
                GanColorizer.colorize(src, hintRgb, hintMask)
            } else {
                GanColorizer.colorize(src)
            }
            // 后处理失败不应该丢掉上色结果，降级返回原结果
            try {
                GanPostProcess.process(colored, src)
            } catch (e: Throwable) {
                colored
            }
        } catch (e: Throwable) {
            // 用 Throwable 而不是 Exception：onnxruntime 的 native 层可能抛 Error
            lastGanError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    // =====================================================================
    // 保存（复用 MediaStore，与 MainActivity 同风格）
    // =====================================================================
    private fun saveCurrent() {
        val bmp = results[currentIndex]
        if (bmp == null) {
            toast("当前页还没有结果")
            return
        }
        val name = "mangacolor_gan_${System.currentTimeMillis()}_p${currentIndex + 1}.png"
        val values = ContentValues().apply {
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

    // =====================================================================
    // 小工具
    // =====================================================================
    private fun setBusy(value: Boolean) {
        busy = value
        btnColorize.isEnabled = !value && pages.isNotEmpty()
        btnBatch.isEnabled = !value && pages.isNotEmpty()
        btnPick.isEnabled = !value
        btnStop.isEnabled = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) refreshEditButtons() else {
            btnUndo.isEnabled = false
            btnClear.isEnabled = false
            btnPrev.isEnabled = false
            btnNext.isEnabled = false
        }
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this@GanActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
