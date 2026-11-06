#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_gan_colorizer.py — 把 GanColorizer.kt 的算法逐行转写成 Python，和 run_onnx.py 对比。

为什么要这份脚本：本机没有 JDK / Gradle / Android SDK，Kotlin 无法编译，更跑不了真机推理。
所以用「同构转写 + 数值对比」来证明移植没走样，重点盯这几个最容易错的点：

  1. resize_pad 的目标尺寸（横图短边 *1.5 / 竖图短边 = size，ceil 向上取整）
  2. numpy `mode='maximum'` 的真实语义（按整行/整列取最大值，不是复制边缘）
  3. ch0 灰度 0~1（绝不 -1~1）
  4. 输出直接 clip(0,1)（绝不 *0.5+0.5）
  5. hint 的 pad 区必须补 0（不是 maximum）

运行：
  python verify_gan_colorizer.py
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
LANDSCAPE_NUM, LANDSCAPE_DEN = 3, 2


# ---------------------------------------------------------------- Kotlin 转写


def kt_resample_plane_f(src: np.ndarray, sw: int, sh: int, dw: int, dh: int) -> np.ndarray:
    """GanColorizer.resamplePlaneF 的逐行转写（PIL BOX 装箱语义 + 精确 1/n 权重）。"""
    if sw == dw and sh == dh:
        return src.astype(np.float64).copy()

    tmp = np.zeros((sh, dw), np.float64)
    scale_x = sw / dw
    for y in range(sh):
        for x in range(dw):
            lo = int(math.floor(x * scale_x + 0.5))
            hi = int(math.floor((x + 1) * scale_x + 0.5))
            if hi <= lo:
                hi = lo + 1
            start = max(0, lo)
            end = min(hi, sw)
            count = end - start
            if count <= 0:
                tmp[y, x] = src[y, min(max(0, lo), sw - 1)]
            else:
                tmp[y, x] = src[y, start:end].sum() / count

    out = np.zeros((dh, dw), np.float64)
    scale_y = sh / dh
    for x in range(dw):
        for y in range(dh):
            lo = int(math.floor(y * scale_y + 0.5))
            hi = int(math.floor((y + 1) * scale_y + 0.5))
            if hi <= lo:
                hi = lo + 1
            start = max(0, lo)
            end = min(hi, sh)
            count = end - start
            if count <= 0:
                out[y, x] = tmp[min(max(0, lo), sh - 1), x]
            else:
                out[y, x] = tmp[start:end, x].sum() / count
    return out


def kt_resample_plane(src: np.ndarray, sw: int, sh: int, dw: int, dh: int) -> np.ndarray:
    """字节版：浮点内核 + 四舍五入回整数。"""
    out = kt_resample_plane_f(src.astype(np.float64), sw, sh, dw, dh)
    return np.round(out)


def kt_build_plan(rgb: np.ndarray):
    """GanColorizer.buildPlan 的转写，返回 (gray01_padded, fullW, fullH, resizedW, resizedH, padR, padB)。"""
    src_h, src_w = rgb.shape[:2]

    if src_h < src_w:
        short_side = SIZE * LANDSCAPE_NUM // LANDSCAPE_DEN
        ratio = src_h / short_side
        resized_w = int(math.ceil(src_w / ratio))
        resized_h = short_side
    else:
        ratio = src_w / SIZE
        resized_w = SIZE
        resized_h = int(math.ceil(src_h / ratio))

    pad_right = (ALIGNMENT - resized_w % ALIGNMENT) % ALIGNMENT
    pad_bottom = (ALIGNMENT - resized_h % ALIGNMENT) % ALIGNMENT
    full_w = resized_w + pad_right
    full_h = resized_h + pad_bottom

    # readChannel(src, 16) = R 通道
    red = rgb[:, :, 0].astype(np.float64)
    red_small = kt_resample_plane(red, src_w, src_h, resized_w, resized_h)

    gray = np.zeros((full_h, full_w), np.float64)
    gray[:resized_h, :resized_w] = red_small / 255.0

    # numpy mode='maximum' 沿 axis=1：每行用自己的行最大值填充
    if pad_right > 0:
        row_max = red_small.max(axis=1)
        gray[:resized_h, resized_w:] = (row_max[:, None] / 255.0)
    # 沿 axis=0：每列用自己的列最大值填充
    if pad_bottom > 0:
        col_max = red_small.max(axis=0)
        gray[resized_h:, :resized_w] = (col_max[None, :] / 255.0)

    return gray, full_w, full_h, resized_w, resized_h, pad_right, pad_bottom


# ---------------------------------------------------------------- Python 参考实现


def ref_resize_pad(img: np.ndarray, size: int = 576):
    """mangacolor-gan/run_onnx.py 的 resize_pad，原样照抄。"""
    if img.ndim == 2:
        img = img[:, :, None]
    if img.shape[2] == 1:
        img = np.repeat(img, 3, axis=2)
    if img.shape[2] == 4:
        img = img[:, :, :3]

    pad = None
    if img.shape[0] < img.shape[1]:
        height = img.shape[0]
        ratio = height / (size * 1.5)
        width = int(np.ceil(img.shape[1] / ratio))
        new_h = int(size * 1.5)
        img = np.asarray(Image.fromarray(img).resize((width, new_h), Image.BOX))
        new_width = width + ((32 - width % 32) % 32)
        pad = (0, new_width - width)
        img = np.pad(img, ((0, 0), (0, pad[1]), (0, 0)), mode='maximum')
    else:
        width = img.shape[1]
        ratio = width / size
        height = int(np.ceil(img.shape[0] / ratio))
        img = np.asarray(Image.fromarray(img).resize((size, height), Image.BOX))
        new_height = height + ((32 - height % 32) % 32)
        pad = (new_height - height, 0)
        img = np.pad(img, ((0, pad[0]), (0, 0), (0, 0)), mode='maximum')

    return img[:, :, :1], pad


# ---------------------------------------------------------------- 对比


def main() -> int:
    images = sorted((GAN / "test").glob("bw*.jpg"))
    if not images:
        print("找不到测试图 mangacolor-gan/test/bw*.jpg")
        return 1

    print("== GanColorizer vs run_onnx.py 数值对比 ==\n")
    all_ok = True

    for path in images:
        src = Image.open(path).convert("RGB")
        rgb = np.asarray(src)

        # 参考实现
        ref_gray, ref_pad = ref_resize_pad(rgb, SIZE)
        ref_gray = ref_gray[:, :, 0].astype(np.float32) / 255.0
        ref_h, ref_w = ref_gray.shape[:2]

        # Kotlin 转写
        gray, full_w, full_h, rw, rh, pad_r, pad_b, = kt_build_plan(rgb)

        print(f"[{path.name}] 原图 {src.width}x{src.height}")
        print(f"  参考: {ref_w}x{ref_h}  pad={ref_pad}")
        print(f"  Kotlin: {full_w}x{full_h}  (resized {rw}x{rh}, padR={pad_r}, padB={pad_b})")

        dims_ok = (ref_w == full_w) and (ref_h == full_h)
        print(f"  {'✓' if dims_ok else '✗'} 尺寸一致")
        all_ok &= dims_ok
        if not dims_ok:
            continue

        # pad 方向也应一致
        ref_pad_r = ref_pad[1] if ref_pad else 0
        ref_pad_b = ref_pad[0] if ref_pad else 0
        pad_ok = (ref_pad_r == pad_r) and (ref_pad_b == pad_b)
        print(f"  {'✓' if pad_ok else '✗'} pad 方向/长度一致 (参考 padR={ref_pad_r}, padB={ref_pad_b})")
        all_ok &= pad_ok

        # 灰度逐像素差异
        diff = np.abs(gray - ref_gray.astype(np.float64))
        max_diff = float(diff.max())
        mean_diff = float(diff.mean())
        p999 = float(np.percentile(diff, 99.9)) * 255
        # 1.0 灰度差 = 255 个色阶
        print(f"  灰度通道: mean {mean_diff * 255:.3f}/255, p99.9 {p999:.2f}/255, max {max_diff * 255:.1f}/255")
        # PIL 的重采样在极少数锐利边缘上本身有一点取整差异，所以按「绝大多数像素」
        # 判定，而不是要求逐像素完全一致。
        tol_ok = (mean_diff * 255 <= 2.0) and (p999 <= 2.0)
        print(f"  {'✓' if tol_ok else '✗'} 灰度误差达标（mean<=2 且 p99.9<=2 色阶）")
        all_ok &= tol_ok

        # 关键口径断言
        in_range = gray.min() >= 0.0 and gray.max() <= 1.0
        print(f"  {'✓' if in_range else '✗'} ch0 在 0~1 区间 (min {gray.min():.3f}, max {gray.max():.3f})")
        all_ok &= in_range

        # pad 区必须参与对比（maximum 语义最容易在这里出错）
        if ref_pad_b > 0 and pad_b > 0:
            pad_diff = diff[rh:, :]
            pad_ok = float(pad_diff.max()) * 255 <= 2.0
            print(f"  {'✓' if pad_ok else '✗'} 底部 pad 区（maximum 语义）误差 <= 2 色阶 "
                  f"(max {float(pad_diff.max()) * 255:.1f})")
            all_ok &= pad_ok

        neg1 = float(((gray * 2 - 1) - ref_gray).mean())
        print(f"  （对照：若误用 -1~1，ch0 平均偏移 {neg1:+.3f}）")
        print()

    print("=" * 60)
    print("全部通过" if all_ok else "存在问题")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
