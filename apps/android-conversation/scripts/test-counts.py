#!/usr/bin/env python3
"""汇总各模块 JVM 单测（build/test-results/**/TEST-*.xml）的用例数，供 verify.sh 和 README 引用。"""
import glob
import sys
import xml.etree.ElementTree as ET

total = fail = skip = 0
rows = {}
for path in sorted(glob.glob("**/build/test-results/**/TEST-*.xml", recursive=True)):
    module = path.split("/build/")[0]
    root = ET.parse(path).getroot()
    t, f, s = int(root.get("tests", 0)), int(root.get("failures", 0)) + int(root.get("errors", 0)), int(root.get("skipped", 0))
    r = rows.setdefault(module, [0, 0, 0])
    r[0] += t; r[1] += f; r[2] += s
    total += t; fail += f; skip += s
for module, (t, f, s) in rows.items():
    print(f"{module}: {t} tests, {f} failed, {s} skipped")
print(f"TOTAL: {total} tests, {fail} failed, {skip} skipped")
sys.exit(1 if fail else 0)
