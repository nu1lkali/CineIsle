# -*- coding: utf-8 -*-
"""交叉校验：代码里引用的 R.string.* 是否都在默认 strings.xml 中定义。"""
import io
import os
import re

SRC = r"D:\project\mpvEx-master\app\src\main\java"
RES = r"D:\project\mpvEx-master\app\src\main\res\values\strings.xml"

with io.open(RES, encoding="utf-8") as f:
    defined = set(re.findall(r'<string name="([^"]+)"', f.read()))

used = {}
for dirpath, _d, filenames in os.walk(SRC):
    for fn in filenames:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        with io.open(path, encoding="utf-8") as f:
            text = f.read()
        for name in re.findall(r"R\.string\.([A-Za-z0-9_]+)", text):
            used.setdefault(name, path)

missing = sorted(n for n in used if n not in defined)
print("used=%d defined=%d missing=%d" % (len(used), len(defined), len(missing)))
for n in missing:
    print("  MISSING %s  <- %s" % (n, used[n]))
