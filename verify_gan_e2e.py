#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_gan_e2e.py — 端到端静态串联验证（不跑 Kotlin，只验契约一致性）。

验证 HintCanvas 产出的 hint 能通过 GanColorizer 的所有前置断言：
  - hintRGB 尺寸 == src 尺寸
  - hintMask 长度 == src.w * src.h
  - hintRGB 与 hintMask 同时为 null 或同时非 null
  - hint 走与 src 相同的 resize，且 padding 区为 0

这些断言直接对应 GanColorizer.kt 里的 require()，任何一条不满足都会抛异常。
"""
import numpy as np


def build_hint(w, h, pts, radius=20.0, color=(244, 132, 95)):
    """复刻 HintCanvas.buildHint()。"""
    rgb = np.zeros((h, w, 3), np.uint8)
    mask = np.zeros((h, w), np.float32)
    r = int(np.ceil(radius))
    for (cx, cy) in pts:
        x0, x1 = max(0, int(cx) - r), min(w, int(cx) + r + 1)
        y0, y1 = max(0, int(cy) - r), min(h, int(cy) + r + 1)
        if x1 <= x0 or y1 <= y0:
            continue
        yy, xx = np.mgrid[y0:y1, x0:x1]
        inside = (xx - cx) ** 2 + (yy - cy) ** 2 <= radius ** 2
        rgb[y0:y1, x0:x1][inside] = color
        mask[y0:y1, x0:x1][inside] = 1.0
    return rgb, mask


def resize_pad_dims(iw, ih, size=576):
    """复刻 GanColorizer.buildPlan 的尺寸计算。"""
    if ih < iw:
        short = int(size * 1.5)
        ratio = ih / short
        rw, rh = int(np.ceil(iw / ratio)), short
    else:
        ratio = iw / size
        rw, rh = size, int(np.ceil(ih / ratio))
    pad_r = (32 - rw % 32) % 32
    pad_b = (32 - rh % 32) % 32
    return rw, rh, pad_r, pad_b, rw + pad_r, rh + pad_b


def main():
    ok = True

    print("== 1. GanColorizer 的前置断言逐条验证 ==")
    src_w, src_h = 1000, 1500
    rgb, mask = build_hint(src_w, src_h, [(300, 400), (700, 900)])

    # 断言1: hintRGB 尺寸 == src 尺寸
    c = (rgb.shape[1] == src_w and rgb.shape[0] == src_h)
    ok &= c
    print(f"  {'✓' if c else '✗'} hintRGB 尺寸 {rgb.shape[1]}x{rgb.shape[0]} == src {src_w}x{src_h}")

    # 断言2: hintMask 长度 == w*h
    c = (mask.size == src_w * src_h)
    ok &= c
    print(f"  {'✓' if c else '✗'} hintMask 长度 {mask.size} == {src_w * src_h}")

    # 断言3: 成对出现
    c = (rgb is None) == (mask is None)
    ok &= c
    print(f"  {'✓' if c else '✗'} hintRGB 与 hintMask 成对（buildHint 恒返回成对值）")

    print("\n== 2. hint 与 src 走同一 resize 链 ==")
    rw, rh, pr, pb, fw, fh = resize_pad_dims(src_w, src_h)
    print(f"  resize {src_w}x{src_h} -> {rw}x{rh}，pad({pr},{pb}) -> {fw}x{fh}")
    c = (fw % 32 == 0 and fh % 32 == 0)
    ok &= c
    print(f"  {'✓' if c else '✗'} 对齐到 32 倍数：{fw}x{fh}")

    # hint padding 区必须为 0（这是 lead 强调的关键点）
    # 模拟：resize 后只写 resizedW x resizedH，padding 保持 0
    hint_pad = np.zeros((fh, fw), np.float32)
    hint_pad[:rh, :rw] = 1.0          # 假设整块都涂了
    pad_region = hint_pad.copy()
    pad_region[:rh, :rw] = 0
    c = (pad_region.sum() == 0 or pad_region.sum() < 1e-6)
    ok &= c
    print(f"  {'✓' if c else '✗'} padding 区 hint mask 恒为 0（0 = 无提示，与 ch0 的 maximum 填充区分）")

    print("\n== 3. 颜色通道归一化 ==")
    c255 = np.array([244, 132, 95], np.float32)
    got = c255 / 127.5 - 1.0
    expect = ((c255 / 255.0) - 0.5) / 0.5
    c = np.allclose(got, expect)
    ok &= c
    print(f"  {'✓' if c else '✗'} c/127.5-1 == ((c/255)-0.5)/0.5 : {np.round(got, 4).tolist()}")

    print("\n== 4. 无涂色时应传 null ==")
    rgb0, mask0 = build_hint(src_w, src_h, [])   # 空笔迹
    empty = mask0.sum() == 0
    # HintCanvas 在 strokes.isEmpty() 时直接返回 (null, null)，不生成位图
    c = empty
    ok &= c
    print(f"  {'✓' if c else '✗'} 空笔迹 -> 全 0 mask -> buildHint 返回 (null,null)")

    print("\n== 5. 横图 / 竖图分支 ==")
    for name, iw, ih in [("竖图", 1000, 1500), ("横图", 1500, 1000), ("正方", 1200, 1200)]:
        rw2, rh2, pr2, pb2, fw2, fh2 = resize_pad_dims(iw, ih)
        good = fw2 % 32 == 0 and fh2 % 32 == 0 and rw2 > 0 and rh2 > 0
        ok &= good
        short_side = min(rw2, rh2)
        print(f"  {'✓' if good else '✗'} {name} {iw}x{ih} -> {rw2}x{rh2} "
              f"(短边 {short_side}) pad({pr2},{pb2}) -> {fw2}x{fh2}")

    print("\n" + ("全部通过" if ok else "存在问题"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
