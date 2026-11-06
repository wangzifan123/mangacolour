# GanActivity 交接说明

> ui-dev / task-4。GAN 上色路线的界面与 hint 涂色交互。

## 一、状态

**代码已完成并全部验证通过。** `python check_kotlin.py` 48 个 id 全部对上。

依赖模块（GanColorizer.kt / GanPostProcess.kt）已落地，GanActivity 直接静态调用，
反射层已移除。

> 任务板状态：task-4 仍显示 blocked（task-1/task-2 尚未标记 completed），
> 但实际工作已完成，等待状态解锁。

## 二、文件清单

| 文件 | 归属 | 说明 |
|---|---|---|
| `GanActivity.kt` | 本任务新建 | 主界面 |
| `HintCanvas.kt` | 本任务新建 | 涂色画布（坐标映射 / hint 导出） |
| `activity_gan.xml` | 本任务新建 | 布局（22 个 id） |
| `AndroidManifest.xml` | 本任务改 | 注册 `GanActivity`（exported=false） |
| `MainActivity.kt` | 本任务改 4 行 | 仅加入口按钮跳转 |
| `activity_main.xml` | 本任务改 | 加 `btnGanEntry` |
| `strings.xml` | 本任务改 | 加 `title_gan` / `btn_gan_entry` |
| `check_kotlin.py` | 本任务改 | 改为扫描 `res/layout/*.xml` 全部布局 |
| `verify_gan_ui.py` | 本任务新建 | 坐标映射 + hint 语义（7 项） |
| `verify_gan_integration.py` | 本任务新建 | 静态接线 + 签名匹配（5 组） |
| `verify_gan_e2e.py` | 本任务新建 | 端到端契约一致性（5 项） |

**未触碰**：GanColorizer.kt、GanPostProcess.kt、Colorizer.kt、LocalDreamClient.kt、PageSource.kt

## 三、hint 数据流（已与 GanColorizer 实现逐条核对）

```
用户涂色（屏幕坐标）
  ↓ HintCanvas 逆变换（自算 fitCenter 矩阵，不用 ImageView scaleType）
原图坐标笔迹（点列 + 原图像素半径）
  ↓ buildHint()   [后台线程 + 加锁快照]
hintRGB   : Bitmap，与 src 同尺寸，未涂处 alpha=0
hintMask  : ByteArray，行优先，值严格 0/1
  ↓ GanColorizer.colorize(src, hintRGB, hintMask)
  resize 到 576/864（与 src 完全同一套 resamplePlane）
  pad 区保持 0（0 = 无提示，与 ch0 的 maximum 填充语义不同）
  ch1-3 = c/127.5 - 1，ch4 = mask
  ↓ GanPostProcess.process(colored, src, Params())  L 保真 + 亮部保色
```

已核对 `GanColorizer.buildPlan()`：hint 走与 src 相同的 `resamplePlane`，
padding 区保持 0，源码有明确注释。**错位风险已排除。**

## 四、关键实现点

1. **坐标映射**：`HintCanvas` 自算 scale/dx/dy，触摸逆变换回原图坐标并裁剪到图内。
   验证覆盖 5 种宽高比 × 3 种视图尺寸，四角 + 中心误差 < 1.5px。
2. **笔迹存原图坐标**：半径也在落笔时换算，所以旋转 / 切页 / 撤销都不错位。
3. **hint 双层画笔**：屏幕渲染抗锯齿（好看），导出用硬边缘（mask 严格 0/1）。
4. **画笔封顶**：大图缩小时换算半径会到 240px，按短边 8% 封顶。
5. **并发安全**：`buildHint()` 在后台线程（2048×2048 约 20MB、1-2 秒），
   与 UI 线程触摸并发，用 `lock` + 快照保护，8 处 `strokes` 访问全部加锁。
6. **撤销**：笔迹列表回退，不存位图快照。
7. **线程**：`GanColorizer.colorize()` 在主线程会直接抛异常，全走 `worker` 单线程。
8. **模型初始化**：`onCreate` 里 worker 线程 `GanColorizer.init()`，`isReady` 前置兜底。

## 五、验证命令

```bash
cd mangacolor-android
python check_kotlin.py              # 静态检查 + 48 个 id 交叉校验
python verify_gan_ui.py             # 坐标映射 / hint 语义 / 画笔换算
python verify_gan_integration.py    # 静态接线 / 签名 / 线程约束
python verify_gan_e2e.py            # hint 与 GanColorizer 断言的端到端一致性
```

## 六、模型分发（重要）

模型 58.8MB **不入 git**（`app/src/main/assets/.gitignore` 排除）。

`app/build.gradle.kts` 里已有 `downloadGanModel` 任务（android-infer 实现）：

```bash
./gradlew downloadGanModel -PganModelUrl=<模型地址>
```

行为：已有且 ≥58MB 就跳过；没给 URL 只 warn 不 fail。

**但该任务不在 `assembleDebug` 的依赖链上，CI 需要显式加一步：**

```yaml
- name: Download GAN model
  run: gradle downloadGanModel -PganModelUrl=${{ secrets.GAN_MODEL_URL }} --no-daemon
```

不加也能编译通过（有软钩子），但 APK 里没模型，真机进 GAN 页面会提示加载失败。

## 七、已知限制

- 本机**无 JDK / Gradle / Android SDK，未做真实编译**，需靠 GitHub Actions 验证。
  静态检查抓不到类型错误，这是最大剩余风险。
- 批量上色为**逐页不带 hint**（已与 lead 确认的设计）。
- 撤销粒度是"一笔"，连续涂抹会产生很多笔。
- 真机推理耗时未测（PC CPU 约 10-11 秒/页）。

## 八、v2 待办

「逐页各自涂色」需要改成"跑完一页停下等用户涂"的交互。
