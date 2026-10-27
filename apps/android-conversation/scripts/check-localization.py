#!/usr/bin/env python3
"""检查 Android 文案：共享的 UIStrings.json + Android 补充目录 + Kotlin 源码里的中文字面量。

规则（与 iOS 的约定一致：中文原句做键，值为 {en, zh-Hans}，{n} 占位符两种语言必须一致）：
  1. UIStrings.json、UIStringsAndroid.json 每个键同时有 en 与 zh-Hans，占位符集合相同；
  2. 补充目录里的键不能与共享目录里已有的键给出不同的值（共享优先，冲突说明有人悄悄改了文案）；
  3. Kotlin 生产代码里每个含汉字的字符串字面量都必须是目录里的键（避免在界面里硬编码中文）。

只用标准库；不构建、不联网。用法：python3 scripts/check-localization.py
"""
import json
import re
import sys
from pathlib import Path

APP = Path(__file__).resolve().parents[1]
ROOT = APP.parents[1]
SHARED = ROOT / "apps/ios-conversation/Resources/UIStrings.json"
SUPPLEMENT = APP / "core/i18n/src/main/assets/UIStringsAndroid.json"
PLACEHOLDER = re.compile(r"\{[0-9]+\}")
CJK = re.compile(r"[\u4e00-\u9fff]")


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def catalog_problems(name: str, data: dict) -> list[str]:
    problems = []
    for key, langs in data.items():
        if not isinstance(langs, dict) or "en" not in langs or "zh-Hans" not in langs:
            problems.append(f"{name}: 缺 en/zh-Hans: {key}")
            continue
        if set(PLACEHOLDER.findall(langs["en"])) != set(PLACEHOLDER.findall(langs["zh-Hans"])):
            problems.append(f"{name}: 占位符不一致: {key}")
    return problems


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def kotlin_literals() -> list[tuple[Path, str]]:
    found = []
    for path in sorted(APP.rglob("*.kt")):
        rel = path.relative_to(APP).parts
        if "build" in rel or "test" in rel or "androidTest" in rel:
            continue
        code = strip_comments(path.read_text(encoding="utf-8"))
        for m in re.finditer(r'"((?:[^"\\\n]|\\.)*)"', code):
            if CJK.search(m.group(1)):
                found.append((path.relative_to(APP), m.group(1)))
    return found


def main() -> int:
    shared, extra = load(SHARED), load(SUPPLEMENT)
    problems = catalog_problems("UIStrings.json", shared) + catalog_problems("UIStringsAndroid.json", extra)
    for key in sorted(set(shared) & set(extra)):
        if shared[key] != extra[key]:
            problems.append(f"补充目录与共享目录的值冲突（以共享为准，请删除补充项）: {key}")
    keys = set(shared) | set(extra)
    for path, literal in kotlin_literals():
        if literal not in keys:
            problems.append(f"{path}: 中文字面量不在文案目录里: {literal!r}")
    if problems:
        print("FAIL check-localization")
        for p in problems[:50]:
            print("  -", p)
        return 1
    print(f"PASS check-localization: 共享 {len(shared)} 键 + Android 补充 {len(extra)} 键；Kotlin 中文字面量均在目录内")
    return 0


if __name__ == "__main__":
    sys.exit(main())
