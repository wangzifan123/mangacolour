# MangaColor — 安卓漫画上色 App

用 local-dream 给黑白漫画上色的安卓 App。**不改 local-dream 源码**，通过它的本地 HTTP API 驱动。

本仓库的 APK 由 **GitHub Actions 自动构建**，本机无需安装 JDK / Android SDK / Gradle。

---

## 一、它是怎么工作的

```
选一张漫画图
   │
   ├─ ① 切块 512×512，块间重叠 96px
   ├─ ② 逐块调 local-dream 的 img2img（denoise≈0.45）
   ├─ ③ 二维斜坡加权融合，消除接缝
   ├─ ④ 原图灰度正片叠底回贴线条（线条由原始像素保证，100% 不变形）
   └─ 保存到 相册/MangaColor
```

**为什么不用 ControlNet**：local-dream 作者明确拒绝支持（[issue #221](https://github.com/xororz/local-dream/issues/221)，`not_planned`），原话"需要同时加载两个模型并且需要改变模型输入，不会支持"。而低 denoise 的 img2img 本身就只改颜色不动结构，再配合线条回贴，效果等价且线条更可靠。

---

## 二、怎么拿到 APK

### 方式 A：GitHub Actions 自动构建（推荐）

1. 在 GitHub 新建一个仓库（可以私有）
2. 把本目录推上去：
   ```bash
   cd mangacolor-android
   git init
   git add .
   git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```
3. 打开仓库的 **Actions** 标签页，等 `Build APK` 跑完（约 3–5 分钟）
4. 在该次运行页面底部 **Artifacts** 下载 `mangacolor-apk.zip`，解压得到 `app-debug.apk`
5. 传到手机安装（需允许"安装未知来源应用"）

> 也可以在 Actions 页面点 **Run workflow** 手动触发。

### 方式 B：本地构建（需自备环境）

```bash
# 需要 JDK 17 + Android SDK + Gradle 8.7
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

---

## 三、使用前提

**必须先装好 local-dream 并打开、且已在界面上选中并加载一个模型** —— 它的后端只有在模型加载完成后才开始监听 `127.0.0.1:8081`。

- 同机使用：地址保持 `http://127.0.0.1:8081` 即可
- PC 端调试：先在电脑上执行 `adb forward tcp:8081 tcp:8081`

---

## 四、参数说明

| 参数 | 默认 | 说明 |
|---|---|---|
| **上色强度** | 0.45 | **最关键**。0.30~0.35 淡彩保结构；0.40~0.50 推荐；>0.70 会改线条 |
| 切块尺寸 | 512 | 不要改，local-dream 的 SD1.5 基准就是 512 |
| 重叠像素 | 96 | 接缝明显就调到 128 |
| 步数 | 20 | 用 DMD2 模型时可降到 8 |
| CFG | 7.0 | NPU 路径设 1.0 会跳过 unconditional UNet，每步约省一半时间 |
| 线条回贴 | 100% | 降到 60% 可让 AI 的线也露一点 |
| 网点保护 | 0% | 网点发脏时调到 50%，只压暗暗于 50% 灰的像素 |

---

## 五、已验证的事

本机无法编译 Kotlin（无 JDK/SDK），所以做了两层替代验证：

### 1. 静态检查（`check_kotlin.py`）

```
✓ Colorizer.kt        括号平衡 / 无已知陷阱
✓ LocalDreamClient.kt 括号平衡 / 无已知陷阱
✓ MainActivity.kt     括号平衡 / 无已知陷阱
✓ 布局定义 21 个 id，代码使用 21 个，零缺失
```

### 2. 算法等价性验证（`verify_parity.py`）

把 Kotlin 算法逐行转写成 Python，与已验证的 `mangacolor.py` 对比数值：

| 项目 | 结果 |
|---|---|
| 切块起点（6 组尺寸组合） | ✅ 完全一致 |
| 融合权重（4 组参数） | ✅ 最大差异 **0.00e+00** |
| 线条回贴（5 组 strength/threshold） | ✅ 最大像素差 **1**（整数舍入） |
| 页面覆盖完整性 | ✅ 900/900、1200/1200 无遗漏 |

### 3. 协议实现

`LocalDreamClient.kt` 的 SSE 解析与 `mangacolor.py` 同一套逻辑，后者已用模拟后端逐字段验证过（请求 base64 PNG、响应 raw RGB reshape、progress/complete/error 三类事件）。

> ⚠️ **未验证**：本机没有 Android 环境，**APK 尚未真正编译和真机运行过**。首次 Actions 构建可能因 SDK/依赖版本差异需要小修。若构建失败，把 Actions 日志贴给我。

---

## 六、文件结构

```
mangacolor-android/
├── .github/workflows/build.yml      # GitHub Actions 构建流程
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── check_kotlin.py                  # Kotlin 静态检查
├── verify_parity.py                 # Kotlin/Python 算法等价性验证
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/mangacolor/app/
        │   ├── MainActivity.kt      # UI + 流程编排
        │   ├── Colorizer.kt         # 切块/融合/线条回贴
        │   └── LocalDreamClient.kt  # SSE 协议客户端
        └── res/
            ├── layout/activity_main.xml
            ├── values/{strings,colors,themes}.xml
            ├── drawable/ic_launcher_foreground.xml
            └── mipmap-anydpi-v26/ic_launcher.xml
```

---

## 七、局限

1. **颜色是模型"猜"的**，无参考图，同一角色跨页可能发色不一致
2. **512 硬限制**，细网点/小字会糊（线条回贴能救线条，救不回已糊的色块）
3. **耗时随块数线性增长**，1200×1800 约 6 块，真机可能几分钟一张
4. 依赖 local-dream 的 HTTP API，**它改协议时需跟着调**
5. 本 App 只是"遥控器 + 图像处理"，**算力全在 local-dream**，不能脱离它单独使用
