#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_gan_hint.py — 验证 hint 通道的变换链与 src 完全一致（这是 ui-dev 提的风险）。

核心要证明三件事：
  1. hint 用的缩放比例 / pad 偏移和 src 完全同一套（否则用户涂的位置会整体偏移）
  2. hint 的 padding 区域**补 0**（不是 maximum 复制边缘）—— 和 ch0 灰度相反
  3. ch1-3 = ((c/255)-0.5)/0.5，ch4 = mask，无提示处全 0

运行：python verify_gan_hint.py
"""
from __future__ import annotations

import math
import pathlib
import sys

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent
GAN = ROOT.parent / "mangacolor-gan"

SIZE = 576
ALIGNMENT = 32


def kt_resample_plane_f(src, sw, sh, dw, dh):
    """GanColorizer.resamplePlaneF 的转写（PIL BOX 装箱语义 + 精确 1/n 权重）。"""
    if sw == dw and sh == dh:
        return np.asarray(src, np.float64).copy()

    tmp = np.zeros((sh, dw), np.float64)
    sx = sw / dw
    for y in range(sh):
        for x in range(dw):
            lo = int(math.floor(x * sx + 0.5))
            hi = max(lo + 1, int(math.floor((x + 1) * sx + 0.5)))
            st, en = max(0, lo), min(hi, sw)
            tmp[y, x] = src[y, st:en].sum() / max(1, en - st) if en > st else src[y, min(max(0, lo), sw - 1)]

    out = np.zeros((dh, dw), np.float64)
    sy = sh / dh
    for x in range(dw):
        for y in range(dh):
            lo = int(math.floor(y * sy + 0.5))
            hi = max(lo + 1, int(math.floor((y + 1) * sy + 0.5)))
            st, en = max(0, lo), min(hi, sh)
            out[y, x] = tmp[st:en, x].sum() / max(1, en - st) if en > st else tmp[min(max(0, lo), sh - 1), x]
    return out


def kt_plan(w, h):
    """返回 (resized_w, resized_h, full_w, full_h, pad_r, pad_b)，与 Kotlin 完全一致。"""
    if h < w:
        ss = SIZE * 3 // 2
        ratio = h / ss
        rw = int(math.ceil(w / ratio))
        rh = ss
    else:
        ratio = w / SIZE
        rw = SIZE
        rh = int(math.ceil(h / ratio))
    pr = (ALIGNMENT - rw % ALIGNMENT) % ALIGNMENT
    pb = (ALIGNMENT - rh % ALIGNMENT) % ALIGNMENT
    return rw, rh, rw + pr, rh + pb, pr, pb


def kt_build_hint(hint_rgb, hint_mask, sw, sh, rw, rh, fw, fh):
    """GanColorizer.buildPlan 里 hint 部分的转写。"""
    m = np.asarray(hint_mask, np.float64).reshape(sh, sw)
    mask_bin = (m != 0).astype(np.float64)

    coverage = kt_resample_plane_f(mask_bin, sw, sh, rw, rh)
    chans = []
    for c in range(3):
        plane = np.asarray(hint_rgb, np.float64)[:, :, c] * mask_bin
        chans.append(kt_resample_plane_f(plane, sw, sh, rw, rh))

    color = np.zeros((fh, fw, 3), np.float64)
    mask_out = np.zeros((fh, fw), np.float64)
    for y in range(rh):
        for x in range(rw):
            cov = coverage[y, x]
            if cov <= 0:
                continue
            inv = 1.0 / cov
            for c in range(3):
                val = min(max(chans[c][y, x] * inv, 0.0), 255.0)
                color[y, x, c] = val / 127.5 - 1.0   # ((v/255)-0.5)/0.5 == v/127.5 - 1
            mask_out[y, x] = 1.0
    return color, mask_out


def main() -> int:
    img_path = GAN / "test" / "bw2.jpg"
    src = Image.open(img_path).convert("RGB")
    sw, sh = src.size

    # 造一个「用户涂色」场景：在中间画一块红色矩形（模拟 HintCanvas 的笔迹）
    hint_rgb = np.zeros((sh, sw, 3), np.uint8)
    hint_mask = np.zeros((sh, sw), np.uint8)
    x0, x1 = int(sw * 0.35), int(sw * 0.55)
    y0, y1 = int(sh * 0.40), int(sh * 0.60)
    hint_rgb[y0:y1, x0:x1] = [220, 60, 70]
    hint_mask[y0:y1, x0:x1] = 1

    rw, rh, fw, fh, pr, pb = kt_plan(sw, sh)
    print(f"原图 {sw}x{sh} -> resized {rw}x{rh} -> padded {fw}x{fh} (padR={pr}, padB={pb})")
    print(f"提示矩形 (原图坐标) x[{x0},{x1}) y[{y0},{y1})")

    color, mask = kt_build_hint(hint_rgb, hint_mask, sw, sh, rw, rh, fw, fh)

    all_ok = True

    # 1) hint 的 pad 区必须全 0
    pad_ok = True
    if pr > 0:
        pad_ok &= bool(np.all(color[:, rw:, :] == 0.0)) and bool(np.all(mask[:, rw:] == 0.0))
    if pb > 0:
        pad_ok &= bool(np.all(color[rh:, :, :] == 0.0)) and bool(np.all(mask[rh:, :] == 0.0))
    print(f"  {'✓' if pad_ok else '✗'} hint 的 padding 区全部为 0（不是 maximum 复制边缘）")
    all_ok &= pad_ok

    # 2) 提示区域的位置必须跟着 src 的同一套变换走（缩放比例一致）
    ys, xs = np.where(mask > 0)
    if len(ys):
        # 期望：原图矩形按同一比例 + 无偏移映射过来
        ex_x0 = x0 * rw / sw
        ex_x1 = x1 * rw / sw
        ex_y0 = y0 * rh / sh
        ex_y1 = y1 * rh / sh
        got_x0, got_x1 = xs.min(), xs.max() + 1
        got_y0, got_y1 = ys.min(), ys.max() + 1
        tol = 2.0
        pos_ok = (abs(got_x0 - ex_x0) <= tol and abs(got_x1 - ex_x1) <= tol
                  and abs(got_y0 - ex_y0) <= tol and abs(got_y1 - ex_y1) <= tol)
        print(f"  {'✓' if pos_ok else '✗'} 提示位置与 src 同变换链（无偏移）")
        print(f"      期望 x[{ex_x0:.1f},{ex_x1:.1f}) y[{ex_y0:.1f},{ex_y1:.1f})")
        print(f"      实际 x[{got_x0},{got_x1}) y[{got_y0},{got_y1})")
        all_ok &= pos_ok
    else:
        print("  ✗ 提示区域在缩放后消失了")
        all_ok = False

    # 3) 提示颜色应该还原成原始颜色对应的 [-1,1] 值
    vals = color[mask > 0]
    if len(vals):
        mean_rgb = vals.reshape(-1, 3).mean(axis=0)
        # 原始 [220,60,70] -> [0.725, -0.529, -0.451]
        expect = np.array([220, 60, 70]) / 127.5 - 1.0
        color_ok = bool(np.allclose(mean_rgb, expect, atol=0.02))
        print(f"  {'✓' if color_ok else '✗'} 提示颜色编码正确")
        print(f"      期望 {np.round(expect, 3).tolist()}  实际 {np.round(mean_rgb, 3).tolist()}")
        all_ok &= color_ok

        inrange = bool(vals.min() >= -1.0 and vals.max() <= 1.0)
        print(f"  {'✓' if inrange else '✗'} ch1-3 在 [-1,1] 区间 (min {vals.min():.3f}, max {vals.max():.3f})")
        all_ok &= inrange

    # 4) 无提示区域 ch1-4 全 0
    zero_ok = bool(np.all(color[mask == 0] == 0.0))
    print(f"  {'✓' if zero_ok else '✗'} 无提示区域 ch1-3 全 0")
    all_ok &= zero_ok

    # 5) 对比：如果 hint 用 maximum 补边会怎样（反例，证明这个坑真实存在）
    if pb > 0:
        wrong = np.pad(mask[:rh, :rw], ((0, pb), (0, 0)), mode='maximum')
        diff_cells = int((wrong != mask).sum())
        print(f"  （对照：若 hint 错用 maximum 补边，会有 {diff_cells} 个像素被误标成「有提示」）")

    print()
    print("=" * 60)
    print("全部通过" if all_ok else "存在问题")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
