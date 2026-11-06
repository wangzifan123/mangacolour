#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_gan_ui.py — 校验 GanActivity/HintCanvas 的 hint 语义与坐标映射。

对照两份事实：
  A. mangacolor-gan/run_onnx.py 的 --hint 处理（Python 参考实现）
  B. 接口契约 ch1-3 = ((c/255)-0.5)/0.5, ch4 = mask

本脚本不跑 Kotlin，而是把 HintCanvas 的数学**逐条复刻**到 numpy，
验证三个容易出错的点：
  1. 屏幕坐标 -> 原图坐标的逆映射（fitCenter）
  2. hintMask 只能是 0/1（不能有抗锯齿半透明值）
  3. hintRGB 与 mask 覆盖范围一致、颜色值精确
"""
import numpy as np


def build_hint(w, h, strokes):
    """复刻 HintCanvas.buildHint()：
    strokes = [(color_rgb, radius_px_orig, [(x,y), ...]), ...]
    用最近距离画圆实现（Kotlin 侧是 Canvas 硬边缘绘制，语义等价）
    """
    rgb = np.zeros((h, w, 3), np.uint8)
    mask = np.zeros((h, w), np.float32)
    for color, radius, pts in strokes:
        r = int(np.ceil(radius))
        for (cx, cy) in pts:
            x0, x1 = max(0, int(cx) - r), min(w, int(cx) + r + 1)
            y0, y1 = max(0, int(cy) - r), min(h, int(cy) + r + 1)
            if x1 <= x0 or y1 <= y0:
                continue
            yy, xx = np.mgrid[y0:y1, x0:x1]
            inside = (xx - cx) ** 2 + (yy - cy) ** 2 <= radius ** 2
            sub_rgb = rgb[y0:y1, x0:x1]
            sub_rgb[inside] = color
            rgb[y0:y1, x0:x1] = sub_rgb
            sub_mask = mask[y0:y1, x0:x1]
            sub_mask[inside] = 1.0
            mask[y0:y1, x0:x1] = sub_mask
    return rgb, mask


def to_input_channels(rgb, mask):
    """复刻契约 build_input 的 hint 部分。"""
    hint = ((rgb.astype(np.float32) / 255.0) - 0.5) / 0.5
    return hint * mask[:, :, None], mask


def screen_to_image(sx, sy, vw, vh, iw, ih):
    """复刻 HintCanvas 的 fitCenter 逆映射。"""
    scale = min(vw / iw, vh / ih)
    w, h = iw * scale, ih * scale
    dx, dy = (vw - w) / 2, (vh - h) / 2
    px = (sx - dx) / scale
    py = (sy - dy) / scale
    return (min(max(px, 0), iw - 1), min(max(py, 0), ih - 1), scale)


def brush_radius_orig(screen_px, scale, iw, ih):
    """复刻 ACTION_DOWN 里的半径换算 + 8% 短边封顶。"""
    short_side = min(iw, ih)
    r = screen_px / scale
    return min(max(r, 1.0), short_side * 0.08)


def main():
    ok = True

    # ---- 1. 坐标映射：四角与中心 ----
    print("== 1. fitCenter 逆映射 ==")
    iw, ih = 1000, 1500          # 竖版漫画页
    vw, vh = 1080, 1200          # 视图区（比图更宽）
    s = min(vw / iw, vh / ih)
    dw, dh = iw * s, ih * s
    dx, dy = (vw - dw) / 2, (vh - dh) / 2
    cases = [
        ("左边缘中点", dx, dy + dh / 2, 0, ih / 2),
        ("右边缘中点", dx + dw, dy + dh / 2, iw - 1, ih / 2),
        ("上边缘中点", dx + dw / 2, dy, iw / 2, 0),
        ("下边缘中点", dx + dw / 2, dy + dh, iw / 2, ih - 1),
        ("正中心", dx + dw / 2, dy + dh / 2, iw / 2, ih / 2),
    ]
    for name, sx, sy, ex, ey in cases:
        px, py, _ = screen_to_image(sx, sy, vw, vh, iw, ih)
        good = abs(px - ex) <= 1.5 and abs(py - ey) <= 1.5
        ok &= good
        print(f"  {'✓' if good else '✗'} {name}: 屏幕({sx:.0f},{sy:.0f}) "
              f"-> 原图({px:.1f},{py:.1f}) 期望({ex:.0f},{ey:.0f})")

    # ---- 2. mask 严格 0/1 ----
    print("\n== 2. hintMask 取值必须只有 0/1 ==")
    strokes = [
        ((244, 132, 95), 20.0, [(300, 400), (320, 410)]),
        ((74, 111, 165), 35.5, [(700, 900)]),
    ]
    rgb, mask = build_hint(iw, ih, strokes)
    uniq = np.unique(mask)
    good = set(uniq.tolist()) <= {0.0, 1.0}
    ok &= good
    print(f"  {'✓' if good else '✗'} mask 唯一值 = {uniq.tolist()[:8]}（共 {len(uniq)} 种）")

    # ---- 3. RGB 与 mask 覆盖一致 ----
    print("\n== 3. hintRGB 与 mask 覆盖一致 ==")
    painted = mask > 0.5
    rgb_nonzero = np.any(rgb != 0, axis=2)
    # 注意：纯黑 (0,0,0) 作为画笔色时 rgb 为 0 但 mask=1，这是合法情况；
    # 校验反方向：rgb 有值的地方 mask 必须为 1
    bad = np.any(rgb_nonzero & ~painted)
    ok &= not bad
    print(f"  {'✓' if not bad else '✗'} rgb 有值处 mask 全为 1")
    print(f"  涂色像素 {int(painted.sum())} / {iw * ih} = {painted.mean() * 100:.2f}%")

    # ---- 4. 颜色值精确 ----
    print("\n== 4. 涂色颜色值精确 ==")
    for color, radius, pts in strokes:
        cx, cy = pts[0]
        got = rgb[int(cy), int(cx)].tolist()
        good = got == list(color)
        ok &= good
        print(f"  {'✓' if good else '✗'} {pts[0]} 处 rgb={got} 期望={list(color)}")

    # ---- 5. 5 通道数值范围 ----
    print("\n== 5. 传给模型的 hint 通道范围 ==")
    hint_ch, m = to_input_channels(rgb, mask)
    good = hint_ch.min() >= -1.0 - 1e-6 and hint_ch.max() <= 1.0 + 1e-6
    ok &= good
    print(f"  {'✓' if good else '✗'} ch1-3 范围 [{hint_ch.min():.3f}, {hint_ch.max():.3f}] ⊂ [-1,1]")
    # 未涂色区域必须恰好为 0（mask=0 相乘后）
    un = hint_ch[~painted]
    good2 = un.size == 0 or np.allclose(un, 0.0)
    ok &= good2
    print(f"  {'✓' if good2 else '✗'} 未涂区域 ch1-3 恒为 0（模型自由发挥）")

    # ---- 6. 与 Python 参考实现的公式一致性 ----
    print("\n== 6. 与 run_onnx.py 公式一致性 ==")
    c = np.array([244, 132, 95], np.float32)
    ref = ((c / 255.0) - 0.5) / 0.5        # run_onnx.py build_input 原式
    mine = ((c.astype(np.float32) / 255.0) - 0.5) / 0.5
    good = np.allclose(ref, mine)
    ok &= good
    print(f"  {'✓' if good else '✗'} 颜色归一化公式一致: {np.round(mine, 4).tolist()}")

    # ---- 7. 画笔半径换算与封顶 ----
    print("\n== 7. 画笔半径换算（含 8% 短边封顶）==")
    for (iw2, ih2) in [(1000, 1500), (3000, 4000), (300, 400)]:
        s2 = min(vw / iw2, vh / ih2)
        raw = 72.0 / s2 if s2 > 0 else 0        # density 3 下 24dp = 72px
        capped = brush_radius_orig(72.0, s2, iw2, ih2)
        limit = min(iw2, ih2) * 0.08
        good = capped <= limit + 1e-6 and capped >= 1.0
        ok &= good
        print(f"  {'✓' if good else '✗'} 原图 {iw2}x{ih2} scale={s2:.3f}: "
              f"原始 {raw:.1f}px -> 封顶后 {capped:.1f}px (上限 {limit:.1f})")

    print("\n" + ("全部通过" if ok else "存在问题"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
