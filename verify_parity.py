#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_parity.py — 验证 Kotlin 版算法与 Python 参考实现等价。

无法在本机编译 Kotlin，所以把 Kotlin 的算法逻辑逐行转写成 Python（保持同构），
再与已验证的 mangacolor.py 实现对比数值结果。这能抓出移植时最容易错的：
  - 权重斜坡的索引方向
  - 切块起点的边界处理
  - 亮度公式（Rec.601）
  - 正片叠底的 threshold 归一化
"""
import pathlib
import sys

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent
REF = ROOT.parent / "mangacolor" / "mangacolor.py"


# ---------- Python 参考实现（来自已验证的 mangacolor.py） ----------
def ref_axis_positions(total, tile, overlap):
    if total <= tile:
        return [0]
    step = max(1, tile - overlap)
    positions = []
    x = 0
    while x + tile < total:
        positions.append(x)
        x += step
    positions.append(total - tile)
    seen, out = set(), []
    for p in positions:
        if p not in seen:
            seen.add(p)
            out.append(p)
    return out


def ref_ramp(tile, overlap):
    if overlap <= 0:
        return np.ones((tile, tile), dtype=np.float32)
    one_d = np.ones(tile, dtype=np.float32)
    ramp = np.linspace(0.0, 1.0, overlap + 2, dtype=np.float32)[1:-1]
    one_d[:overlap] = ramp
    one_d[-overlap:] = ramp[::-1]
    return np.outer(one_d, one_d)


def ref_lines(colored, original, strength, threshold):
    colored = colored.convert("RGB")
    if colored.size != original.size:
        colored = colored.resize(original.size, Image.LANCZOS)
    gray = np.asarray(original.convert("L"), dtype=np.float32) / 255.0
    line = 1.0 - gray
    if threshold > 0:
        line = np.clip((line - threshold) / max(1e-6, 1.0 - threshold), 0.0, 1.0)
    factor = 1.0 - float(np.clip(strength, 0.0, 1.0)) * line
    out = np.asarray(colored, dtype=np.float32) * factor[..., None]
    return Image.fromarray(out.clip(0, 255).astype(np.uint8), "RGB")


# ---------- Kotlin 实现的同构转写 ----------
def kt_axis_positions(total, tile, overlap):
    if total <= tile:
        return [0]
    step = max(1, tile - overlap)
    out = []
    x = 0
    while x + tile < total:
        out.append(x)
        x += step
    out.append(total - tile)
    # Kotlin 用 distinct()，保持首次出现顺序
    seen, res = set(), []
    for v in out:
        if v not in seen:
            seen.add(v)
            res.append(v)
    return res


def kt_ramp(tile, overlap):
    w = np.zeros(tile * tile, dtype=np.float32)
    if overlap <= 0:
        w[:] = 1.0
        return w.reshape(tile, tile)
    ramp = np.array([(i + 1) / (overlap + 1) for i in range(overlap)], dtype=np.float32)
    for y in range(tile):
        wy = ramp[y] if y < overlap else (ramp[tile - 1 - y] if y >= tile - overlap else 1.0)
        for x in range(tile):
            wx = ramp[x] if x < overlap else (ramp[tile - 1 - x] if x >= tile - overlap else 1.0)
            w[y * tile + x] = wx * wy
    return w.reshape(tile, tile)


def kt_lines(colored, original, strength, threshold):
    """复刻 Colorizer.recomposeLines 的整数运算。"""
    w, h = colored.size
    src = original.resize((w, h)) if original.size != (w, h) else original
    cp = np.asarray(colored.convert("RGB"), dtype=np.int32)
    op = np.asarray(src.convert("RGB"), dtype=np.int32)
    s = min(max(strength, 0.0), 1.0)
    gray = (op[..., 0] * 0.299 + op[..., 1] * 0.587 + op[..., 2] * 0.114) / 255.0
    line = 1.0 - gray
    if threshold > 0:
        line = np.clip((line - threshold) / max(1e-6, 1.0 - threshold), 0.0, 1.0)
    factor = 1.0 - s * line
    out = np.stack([
        np.clip((cp[..., 0] * factor).astype(np.int32), 0, 255),
        np.clip((cp[..., 1] * factor).astype(np.int32), 0, 255),
        np.clip((cp[..., 2] * factor).astype(np.int32), 0, 255),
    ], axis=-1)
    return Image.fromarray(out.astype(np.uint8), "RGB")


def main() -> int:
    ok = True

    print("== 1) 切块起点 ==")
    cases = [(900, 512, 96), (1200, 512, 96), (400, 512, 96),
             (1024, 512, 0), (1500, 512, 128), (512, 512, 96)]
    for total, tile, ov in cases:
        a, b = ref_axis_positions(total, tile, ov), kt_axis_positions(total, tile, ov)
        same = a == b
        ok &= same
        print(f"  {'✓' if same else '✗'} total={total} tile={tile} ov={ov} -> {a}"
              + ("" if same else f"  Kotlin={b}"))

    print("\n== 2) 融合权重 ==")
    for tile, ov in [(512, 96), (256, 32), (512, 0), (128, 60)]:
        a, b = ref_ramp(tile, ov), kt_ramp(tile, ov)
        diff = float(np.abs(a - b).max())
        same = diff < 1e-5
        ok &= same
        print(f"  {'✓' if same else '✗'} tile={tile} ov={ov} 最大差异={diff:.2e}"
              f"  中心={a[tile//2, tile//2]:.4f} 角={a[0,0]:.4f}")

    print("\n== 3) 线条回贴 ==")
    orig = Image.fromarray(np.random.RandomState(0).randint(0, 256, (64, 64, 3), dtype=np.uint8))
    col = Image.fromarray(np.full((64, 64, 3), 180, np.uint8))
    for s, th in [(0.0, 0.0), (0.5, 0.0), (1.0, 0.0), (1.0, 0.5), (0.7, 0.3)]:
        a = np.asarray(ref_lines(col, orig, s, th), np.int32)
        b = np.asarray(kt_lines(col, orig, s, th), np.int32)
        # 允许 ±2 的舍入差（numpy float 乘法 vs Kotlin int 截断）
        diff = int(np.abs(a - b).max())
        same = diff <= 2
        ok &= same
        print(f"  {'✓' if same else '✗'} strength={s} threshold={th} 最大像素差={diff}")

    print("\n== 4) 完整流程尺寸一致性 ==")
    page = Image.new("RGB", (900, 1200), (255, 255, 255))
    xs = ref_axis_positions(page.width, 512, 96)
    ys = ref_axis_positions(page.height, 512, 96)
    kxs = kt_axis_positions(page.width, 512, 96)
    kys = kt_axis_positions(page.height, 512, 96)
    cover_x = set()
    for x in kxs:
        cover_x.update(range(x, min(x + 512, page.width)))
    cover_y = set()
    for y in kys:
        cover_y.update(range(y, min(y + 512, page.height)))
    full = len(cover_x) == page.width and len(cover_y) == page.height
    ok &= full
    print(f"  {'✓' if full else '✗'} 覆盖完整: 横向 {len(cover_x)}/{page.width}, 纵向 {len(cover_y)}/{page.height}")
    print(f"  块数 {len(kxs)}x{len(kys)}={len(kxs)*len(kys)}")

    print("\n" + ("Kotlin 与 Python 算法一致 ✓" if ok else "存在不一致 ✗"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
