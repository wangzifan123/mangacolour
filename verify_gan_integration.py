#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_gan_integration.py — 验证 GanActivity 与依赖模块的静态接线。

依赖已落地（GanColorizer.kt / GanPostProcess.kt），GanActivity 改为直接静态调用。
本脚本从源码里提取**真实的公开签名**，再核对 GanActivity 的调用点是否匹配，
并确认反射层已彻底移除。

比反射年代的 verify_gan_bridge.py 更严格：直接读 Kotlin 源码解析签名。
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent
KT = ROOT / "app/src/main/java/com/mangacolor/app"


def read(name):
    return (KT / name).read_text(encoding="utf-8")


def public_funs(src):
    """抓 `fun name(...)` 的签名（只看缩进 4 格、非 private 的）。"""
    out = {}
    for m in re.finditer(r'^\s{4}(?:suspend\s+)?fun\s+(\w+)\s*\(([^)]*)\)', src, re.M):
        name, params = m.group(1), m.group(2)
        if name.startswith("_"):
            continue
        types = []
        for p in params.split(","):
            p = p.strip()
            if not p:
                continue
            # 去掉默认值，取类型
            p = p.split("=")[0].strip()
            parts = p.split(":")
            types.append(parts[-1].strip() if len(parts) > 1 else p)
        out.setdefault(name, []).append(types)
    return out


def main():
    ok = True

    colorizer_src = read("GanColorizer.kt")
    post_src = read("GanPostProcess.kt")
    act_src = read("GanActivity.kt")

    print("== 1. 依赖模块公开签名（从源码解析）==")
    cz = public_funs(colorizer_src)
    pp = public_funs(post_src)

    for fname in ["init", "colorize", "release"]:
        sigs = cz.get(fname)
        good = bool(sigs)
        ok &= good
        print(f"  {'✓' if good else '✗'} GanColorizer.{fname}: {sigs}")

    sigs = pp.get("process")
    good = bool(sigs)
    ok &= good
    print(f"  {'✓' if good else '✗'} GanPostProcess.process: {sigs}")

    print("\n== 2. GanActivity 的调用点匹配 ==")
    checks = [
        ("GanColorizer.init(applicationContext)", r'GanColorizer\.init\(applicationContext\)')
            ,
        ("GanColorizer.isReady 前置检查", r'GanColorizer\.isReady'),
        ("GanColorizer.colorize(src, hintRgb, hintMask)",
         r'GanColorizer\.colorize\(src,\s*hintRgb,\s*hintMask\)'),
        ("GanColorizer.colorize(src) 无 hint", r'GanColorizer\.colorize\(src\)'),
        ("GanPostProcess.process(colored, src)", r'GanPostProcess\.process\(colored,\s*src\)'),
    ]
    for label, pattern in checks:
        good = re.search(pattern, act_src) is not None
        ok &= good
        print(f"  {'✓' if good else '✗'} {label}")

    print("\n== 3. 反射层已移除 ==")
    good = "GanBridge" not in act_src
    ok &= good
    print(f"  {'✓' if good else '✗'} 无 GanBridge 残留")
    good = "java.lang.reflect" not in act_src
    ok &= good
    print(f"  {'✓' if good else '✗'} 无反射 import")

    print("\n== 4. 线程约束 ==")
    # GanColorizer.colorize 在主线程会抛异常，必须在 worker 里调用
    good = re.search(r'worker\.execute\s*\{', act_src) is not None
    ok &= good
    print(f"  {'✓' if good else '✗'} 推理在 worker 线程执行")
    # 确认没有在主线程直接调 colorize
    main_thread_bad = bool(re.search(r'fun onCreate[\s\S]{0,3000}?GanColorizer\.colorize', act_src))
    ok &= not main_thread_bad
    print(f"  {'✓' if not main_thread_bad else '✗'} onCreate 内未直接调用 colorize")

    print("\n== 5. hint 成对传参（GanColorizer 对单边传参会抛异常）==")
    good = re.search(r'hintRgb\s*!=\s*null\s*&&\s*hintMask\s*!=\s*null', act_src) is not None
    ok &= good
    print(f"  {'✓' if good else '✗'} 传 hint 前检查 rgb 与 mask 同时非空")

    print("\n" + ("全部通过" if ok else "存在问题"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
