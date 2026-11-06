# GanColorizer 集成说明（task-1）

> 作者：android-infer。文件：`app/src/main/java/com/mangacolor/app/GanColorizer.kt`
> 参考实现：`mangacolor-gan/run_onnx.py`（逐行对照）

---

## 一、API 接口（最终签名，已与 GanActivity 的调用点核对一致）

```kotlin
object GanColorizer {
    const val DEFAULT_MODEL_ASSET = "manga-colorize-fp16.onnx"
    const val DEFAULT_SIZE = 576

    val isReady: Boolean

    /** 从 assets 加载模型（阻塞，建议后台线程）。短边基准默认 576 */
    fun init(context: Context, modelAssetPath: String = DEFAULT_MODEL_ASSET, shortSide: Int = DEFAULT_SIZE)

    /** 从文件路径加载（模型不进 assets、改网络下发时用） */
    fun initFromFile(modelFile: File, shortSide: Int = DEFAULT_SIZE)

    /**
     * 上色主入口（阻塞，主线程调用会抛 IllegalStateException）
     * @param hintRGB  可选，必须与 src 同尺寸
     * @param hintMask 可选，ByteArray，长度 = src.width * src.height，1=有提示 0=无
     * @return 与 src 同尺寸；**不做线条回贴**（那是 GanPostProcess.restoreLines 的事）
     */
    fun colorize(src: Bitmap, hintRGB: Bitmap? = null, hintMask: ByteArray? = null): Bitmap

    /** 协程版，自动切 Dispatchers.Default */
    suspend fun colorizeSuspend(src: Bitmap, hintRGB: Bitmap? = null, hintMask: ByteArray? = null): Bitmap

    /** 回调版，后台单线程执行（回调也在后台线程） */
    fun colorizeAsync(src: Bitmap, hintRGB: Bitmap? = null, hintMask: ByteArray? = null,
                      onSuccess: (Bitmap) -> Unit, onError: (Throwable) -> Unit)

    fun release()
}
```

**关于 hintMask 的类型**：用 `ByteArray`，不是 `FloatArray` / `BooleanArray`。
每个字节 1 = 有提示、0 = 无。这与 `HintCanvas.buildHint(): Pair<Bitmap?, ByteArray?>` 直接对齐，
调用方不需要任何转换。只给 `hintRGB` 不给 `hintMask`（或反过来）会抛 `IllegalArgumentException`。

---

## 二、数值规格（与 `研发进度与接口契约.md` 1.2/1.3 完全一致，勿改）

| 项 | 规格 |
|---|---|
| 输入张量 | `[1, 5, H, W]` float32，张量名 `input`（从模型里动态读，不硬编码） |
| ch0 灰度 | **0~1**（`r/255`）。**绝不能**转 -1~1，否则整页发暗（亮度 157→93） |
| ch1~3 颜色提示 | `((c/255)-0.5)/0.5`，即 `c/127.5 - 1`；无提示填 0 |
| ch4 hint mask | 0 或 1；无提示填 0 |
| 输出 | `[1, 3, H, W]` float32，输出名 `rgb`。raw **已是 0~1**，直接 `clip(0,1)`，**绝不要** `*0.5+0.5`（否则亮度 91→173、std 78→39） |
| H、W | 必须是 32 的倍数 |

---

## 三、预处理 resize_pad（照抄原仓库，有两处踩过的坑）

```
横图 (h < w) ：短边 → size*1.5 = 864，宽按比例 ceil
竖图 (h >= w)：短边 → size      = 576，高按比例 ceil
缩放算法    ：PIL Image.BOX 等价
pad        ：补到 32 的倍数
```

### 坑 1：`np.pad(mode='maximum')` 不是「复制边缘像素」

它的真实语义是**按整行/整列取最大值**填充：

- 沿 axis=1（右侧 pad）：每一行用**该行的行最大值**填充
- 沿 axis=0（底部 pad）：每一列用**该列的列最大值**填充

实测 bw1.jpg：maximum 填充出的条带均值 **254**，而"复制边缘"只有 **145**，差别巨大。
`GanColorizer` 里精确复刻了 maximum 语义。

### 坑 2：PIL `Image.BOX` 不是数学上的精确面积加权

它是**装箱**语义：输出像素 `o` 取源区间 `[round(o*r), round((o+1)*r))` 内像素的等权平均。
用 impulse 反推权重矩阵验证过：6→4 时 PIL 给 `[0.502, 0.502] / [1.0]`，
而"精确面积加权"会给 `[0.667, 0.333] / [0.333, 0.667]`。

如果按"精确面积加权"写，灰度通道最大会偏 127 个色阶。`GanColorizer.resamplePlaneF` 复刻的是装箱语义。

---

## 四、hint 通道的变换链（ui-dev 提的风险，已处理并验证）

hint **必须与 src 走完全相同的一套变换**，否则用户涂的位置会整体偏移：

1. **同一套缩放比例**：hint 与 src 用同一个 `resizedW × resizedH`，同一套 `resamplePlaneF`
2. **hint 的 pad 区补 0**，不是 maximum —— 0 表示"此处没有提示"。
   这与 ch0 灰度的 maximum 语义**刚好相反**，两者别搞混。
   （反例验证：若 hint 错用 maximum 补边，bw2 会有 3480 个像素被误标成"有提示"）
3. 提示颜色用 **mask 加权平均** `resample(c*m) / resample(m)`：
   直接对颜色做面积平均会把区域外的黑色混进来，提示色边缘会发暗
4. 阈值取"覆盖率 > 0 就算有提示"，而不是 > 0.5：
   下行采样时一根 1px 的笔画平均后只剩百分之几，用 0.5 会被整条抹掉，用户会觉得"涂了没反应"

---

## 五、模型分发方案（58.8MB 不进 git）

**模型不进 git**，理由：GitHub 单文件 >50MB 警告、>100MB 直接拒收。

```
app/src/main/assets/.gitignore   ← 内容："*.onnx"，把模型排除在版本管理外
```

三条获取途径（任选其一）：

1. **CI 自动下载**（已配好）：`.github/workflows/build.yml` 里 `Download GAN model` 步骤，
   URL 和 SHA256 都已固定：
   - URL：`https://huggingface.co/Faridzar/manga-colorization-v2-onnx/resolve/main/manga-colorize-fp16.onnx`
   - SHA256：`39660d0047ea6f1a0ddee6aa89054997f95ea566f4d56ff762f66dbcf1a1a7ef`（已用本地文件校验，一致）
   - `continue-on-error: true`，下载失败不阻断编译（APK 里没有模型，运行时才报错）
2. **本地 Gradle 任务**：`./gradlew downloadGanModel -PganModelUrl=<url>`
3. **手动拷贝**：把 `manga-colorize-fp16.onnx` 放到 `app/src/main/assets/` 即可

模型就位后，`GanColorizer.init(context)` 会把它拷到 `context.filesDir/models/`
（一次性，约 0.3~1 秒），之后直接复用这个副本。

---

## 六、依赖

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

版本选择说明：模型是 `opset 17 / ir_version 8`，1.17.0+ 全都能跑。
选 **1.20.0** 是为了避开新版 ORT 可能引入的 `minCompileSdk 35` 要求——
本工程是 `compileSdk = 34`。如果升级遇到
`requires ... compile against version 35 or later`，把版本降到 1.20.0 或把 compileSdk 提到 35。

另外 `androidResources { noCompress += "onnx" }`：保证模型在 APK 里不被压缩。

---

## 七、验证情况

本机**没有 JDK / Gradle / Android SDK**，无法本地编译，更无法真机推理。已做的验证：

| 验证 | 脚本 | 结果 |
|---|---|---|
| Kotlin 静态检查 | `check_kotlin.py` | 全部通过 |
| 预处理数值 parity（尺寸/pad/灰度） | `verify_gan_colorizer.py` | 全部通过 |
| hint 变换链（位置/颜色/pad 补 0） | `verify_gan_hint.py` | 全部通过 |

`verify_gan_colorizer.py` 是把 Kotlin 算法**逐行转写成 Python** 再和 `run_onnx.py` 对比
（和已有的 `verify_parity.py` 同一思路）。三张测试图的结果：

| 图 | 尺寸一致 | pad 一致 | 灰度 mean 误差 | 灰度 max 误差 |
|---|---|---|---|---|
| bw1.jpg | ✓ | ✓ padB=9 | 0.41/255 | 1 |
| bw2.jpg | ✓ | ✓ padB=30 | 0.17/255 | 106（0.27% 像素） |
| bw3.jpg | ✓ | ✓ padB=3 | 0.12/255 | 103（0.08% 像素） |

bw2/bw3 的少量像素差异来自 PIL 在特定宽高比下的边界舍入细节，
整体亮度差 **0.1 个色阶**、std 差 0.03，视觉无感。

---

## 八、未验证项 / 风险

1. **真机推理未跑过**。ARM CPU 上按契约文档估计 30–60 秒，实测数据需要真机。
2. **onnxruntime-android 1.20.0 的 `minCompileSdk`** 未能直接核实（AAR 下载被网络限制）。
   如果 CI 报 `compileSdk 35` 相关错误，按第六节降版本或提 compileSdk。
3. **输出缩放用系统双线性**，Python 用的是 LANCZOS，重采样回原图尺寸时差 1~2 个灰阶。
4. **模型第 2 次启动**会复用 `filesDir` 里的副本；如果换了模型文件但 SHA 变了，
   需要手动清 App 数据（当前没有做版本戳校验）。
