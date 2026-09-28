#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
清理 values-zh-rCN/strings.xml 里已无代码引用的 emby_* 死字符串。

背景：早期 Emby 界面迭代时往 values-zh-rCN 批量塞过 54 个 emby_* 字符串，
后来界面改为直接硬编码中文，这批字符串就再没被 R.string.* 引用过。
release 构建（isMinifyEnabled + lintVital）会被 lint 的 ExtraTranslation
规则整批拦下："emby_home is translated here but not found in default locale"。

处理方式：把 name 以 emby_ 开头的 <string> 条目从该文件里删掉。
多行值用 DOTALL + 非贪婪匹配整段删除，保留文件其余内容与注释。
"""
import re
import sys
from pathlib import Path

RES = Path(r"D:\project\mpvEx-master\app\src\main\res\values-zh-rCN\strings.xml")

raw = RES.read_text(encoding="utf-8")

# <string name="emby_xxx" ...> ... </string>（允许跨行）
pattern = re.compile(
    r'\n?[ \t]*<string\s+name="emby_[^"]*"[^>]*>.*?</string>[ \t]*\n?',
    re.DOTALL,
)

matches = pattern.findall(raw)
cleaned = pattern.sub("\n", raw)

# 压掉连续 3 个以上空行，避免删完留下一串空洞
cleaned = re.sub(r"\n{4,}", "\n\n\n", cleaned)

RES.write_text(cleaned, encoding="utf-8")

print(f"removed {len(matches)} entries")
for m in matches:
    name = re.search(r'name="([^"]+)"', m)
    print("  -", name.group(1) if name else "?")
