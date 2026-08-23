#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 Dart 的 douyin_sign.dart 中提取内嵌 JS 脚本（kABogus / kWebMsSDK），
输出为 core 资源文件，供 Kotlin 端用 Rhino 执行。

用法: python kotlin_re/scripts/extract_douyin_js.py
输出:
  kotlin_re/core/src/main/resources/douyin/abogus.js
  kotlin_re/core/src/main/resources/douyin/webms_sdk.js
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "dart_simple_live" / "simple_live_core" / "lib" / "src" / "scripts" / "douyin_sign.dart"
OUT_DIR = ROOT / "kotlin_re" / "core" / "src" / "main" / "resources" / "douyin"


def extract(text: str, const_name: str) -> str:
    # 匹配: static const <name> = r'''<script>''';
    pattern = re.compile(
        r"static\s+const\s+" + re.escape(const_name) + r"\s*=\s*r'''\n?(.*?)'''\s*;",
        re.DOTALL,
    )
    m = pattern.search(text)
    if not m:
        raise SystemExit(f"未找到 {const_name}")
    return m.group(1)


def main() -> None:
    if not SRC.exists():
        raise SystemExit(f"源文件不存在: {SRC}")
    text = SRC.read_text(encoding="utf-8")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, out in (("kABogus", "abogus.js"), ("kWebMsSDK", "webms_sdk.js")):
        body = extract(text, name)
        target = OUT_DIR / out
        target.write_text(body, encoding="utf-8")
        print(f"OK {target} ({len(body)} bytes, {body.count(chr(10))} lines)")
    print("完成")


if __name__ == "__main__":
    main()