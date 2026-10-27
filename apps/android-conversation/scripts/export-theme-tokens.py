#!/usr/bin/env python3
"""把 iOS 的 CatTheme.swift 令牌导出成 docs/design/mimi-theme-tokens.json（Android 端的独立校验快照）。

用法：
  python3 scripts/export-theme-tokens.py          # 重新生成快照
  python3 scripts/export-theme-tokens.py --check  # 只检查快照是否与 Swift 源一致（verify.sh 使用）

Swift 文件是权威来源；Android 的 ThemeTokens.kt 是手工移植，`TokenSnapshotTest` 逐值比对本快照，
所以任何一端改了数值而另一端没跟上，都会让单测失败。只用标准库，不执行 Swift。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SWIFT = ROOT / "apps/ios-conversation/Sources/DesignSystem/CatTheme.swift"
OUT = ROOT / "docs/design/mimi-theme-tokens.json"

ROLE_FIELDS = [
    "canvas", "card", "surface", "hairline", "track", "ink", "ink2", "ink3",
    "accent", "accentOn", "accentOnPressed", "orangeText",
    "success", "successFill", "attention", "attentionFill", "danger", "dangerFill",
]


def balanced(text: str, start: int) -> str:
    """text[start] 必须是 '('；返回与之配对的括号内的内容。"""
    assert text[start] == "("
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    raise ValueError("括号不配对")


def call_body(text: str, head: str, occurrence: int = 0) -> str:
    idx = -1
    for _ in range(occurrence + 1):
        idx = text.index(head, idx + 1)
    return balanced(text, idx + len(head) - 1)


def strip_comments(s: str) -> str:
    return re.sub(r"//[^\n]*", "", s)


def hexs(value: int) -> str:
    return "#%06X" % value


def num(s: str) -> float:
    return float(s)


def parse_set(body: str) -> dict:
    body = strip_comments(body)
    roles = {}
    for name in ROLE_FIELDS:
        m = re.search(r"\b%s:\s*(0x[0-9A-Fa-f]+)" % name, body)
        assert m, f"缺少 {name}"
        roles[name] = hexs(int(m.group(1), 16))
    kids = re.search(r"kids:\s*\[([^\]]*)\]", body).group(1)
    kids = [hexs(int(k, 16)) for k in re.findall(r"0x[0-9A-Fa-f]+", kids)]
    btn = call_body(body, "button: CatTintedButtonSpec(")
    button = {k: num(v) for k, v in re.findall(r"(\w+):\s*([0-9.]+)", btn)}
    tab = call_body(body, "tabSelectedFill: CatRGBA(")
    tm = re.match(r"\s*(0x[0-9A-Fa-f]+),\s*alpha:\s*([0-9.]+)", tab)
    tab_fill = {"hex": hexs(int(tm.group(1), 16)), "alpha": num(tm.group(2))}
    wash = None
    if re.search(r"wash:\s*nil", body) is None:
        wb = call_body(body, "wash: CatCanvasWash(")
        top = re.search(r"topHex:\s*(0x[0-9A-Fa-f]+)", wb)
        fade = re.search(r"fadeEnd:\s*([0-9.]+)", wb)
        spots = []
        for sm in re.finditer(r"\.init\(([^)]*)\)", wb):
            f = dict(re.findall(r"(\w+):\s*(0x[0-9A-Fa-f]+|[0-9.]+)", sm.group(1)))
            spots.append({"hex": hexs(int(f["hex"], 16)), "alpha": num(f["alpha"]),
                          "centerX": num(f["centerX"]), "centerY": num(f["centerY"]),
                          "radiusX": num(f["radiusX"]), "radiusY": num(f["radiusY"])})
        wash = {"topHex": hexs(int(top.group(1), 16)), "fadeEnd": num(fade.group(1)), "spots": spots}
    return {"roles": roles, "kids": kids, "button": button, "tabSelectedFill": tab_fill, "wash": wash}


def build() -> dict:
    text = SWIFT.read_text(encoding="utf-8")
    logo = re.search(r"logoOrange:\s*UInt32\s*=\s*(0x[0-9A-Fa-f]+)", text)
    result = {
        "_comment": "由 scripts/export-theme-tokens.py 从 CatTheme.swift 生成，请勿手改；Android TokenSnapshotTest 用它校验 ThemeTokens.kt。",
        "source": str(SWIFT.relative_to(ROOT)),
        "logoOrange": hexs(int(logo.group(1), 16)),
        "combos": {},
        "tabSelectedStyle": {},
    }
    for pal_name, prefix, head in [("C", "C", "static let inkWhite = CatThemeTokens("),
                                   ("B", "B", "static let softBlush = CatThemeTokens(")]:
        block = call_body(text, head)
        for mode, label in [("light", "day"), ("dark", "night")]:
            seg = call_body(block, f"{mode}: CatColorSet(")
            result["combos"][f"{prefix}-{label}"] = parse_set(seg)
        style = re.search(r"tabSelectedStyle:\s*\.(\w+)", block).group(1)
        result["tabSelectedStyle"][pal_name] = style
    return result


def main() -> int:
    data = json.dumps(build(), ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != data:
            print("FAIL: docs/design/mimi-theme-tokens.json 与 CatTheme.swift 不一致，请运行 scripts/export-theme-tokens.py", file=sys.stderr)
            return 1
        print("PASS mimi-theme-tokens.json 与 CatTheme.swift 一致")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(data, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
