#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_kotlin.py — Kotlin 源文件的静态健康检查（无编译器时的最低保障）。

检查项：
  1. 括号平衡（先剥离字符串/注释，避免误判）
  2. 常见 Kotlin 陷阱：`?: run { ...; return }`、用 final/val 等软关键字当变量名
  3. R.id / R.layout 引用与资源文件交叉校验
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent


def strip_code(src: str) -> str:
    """去掉字符串字面量与注释，只留结构性字符。"""
    src = re.sub(r'"""[\s\S]*?"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\])*"', '""', src)
    src = re.sub(r'//[^\n]*', '', src)
    src = re.sub(r'/\*[\s\S]*?\*/', '', src)
    return src


def check_brackets(name: str, src: str) -> bool:
    bal = {'{': 0, '(': 0, '[': 0}
    pairs = {'}': '{', ')': '(', ']': '['}
    for ch in strip_code(src):
        if ch in bal:
            bal[ch] += 1
        elif ch in pairs:
            bal[pairs[ch]] -= 1
    ok = all(v == 0 for v in bal.values())
    print(f"  {'✓' if ok else '✗'} {name} 括号平衡 {'' if ok else bal}")
    return ok


def check_traps(name: str, src: str) -> bool:
    ok = True
    # 陷阱1: elvis 里 return —— 会从 lambda 返回而非函数，逻辑易错
    if re.search(r'\?:\s*run\s*\{[^}]*return', src):
        print(f"  ✗ {name} 存在 `?: run {{ ... return }}` 写法（return 只退出 lambda）")
        ok = False
    # 陷阱2: 软关键字当变量名
    for kw in ['final', 'open', 'data', 'value', 'object']:
        if re.search(rf'\bval\s+{kw}\s*=', src):
            print(f"  ✗ {name} 用软关键字 `{kw}` 当变量名")
            ok = False
    if ok:
        print(f"  ✓ {name} 无已知陷阱")
    return ok


def main() -> int:
    kt_dir = ROOT / "app/src/main/java/com/mangacolor/app"
    files = sorted(kt_dir.glob("*.kt"))
    if not files:
        print("没找到 Kotlin 源文件")
        return 1

    print("== Kotlin 静态检查 ==")
    all_ok = True
    for f in files:
        src = f.read_text(encoding="utf-8")
        all_ok &= check_brackets(f.name, src)
        all_ok &= check_traps(f.name, src)

    print("\n== 资源引用交叉校验 ==")
    layout = (ROOT / "app/src/main/res/layout/activity_main.xml").read_text(encoding="utf-8")
    ids = set(re.findall(r'android:id="@\+id/(\w+)"', layout))
    code = "\n".join(f.read_text(encoding="utf-8") for f in files)
    used = set(re.findall(r'R\.id\.(\w+)', code))
    missing = used - ids
    print(f"  布局定义 {len(ids)} 个 id，代码使用 {len(used)} 个")
    if missing:
        print(f"  ✗ 代码引用了不存在的 id: {missing}")
        all_ok = False
    else:
        print("  ✓ 所有 R.id 引用都存在")

    print("\n" + ("全部通过" if all_ok else "存在问题"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
