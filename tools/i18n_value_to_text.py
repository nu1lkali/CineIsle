#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给「以枚举 entries 当下拉选项」的 ListPreference 补 valueToText。

原因：me.zhanghai.compose.preference 的 ListPreference 默认用 toString() 渲染选项，
枚举的 toString() 就是常量名（Seek / PlayPause / Fix / HighQuality …），
于是中文界面的手势设置、解码器设置里会直接露出英文枚举名。
这些枚举本身已经有 @StringRes 字段，只要补一个 valueToText 就能显示中文。
"""
import os
import re

JAVA = r"D:\project\mpvEx-master\app\src\main\java"

# 枚举名 -> 用于取字符串资源的字段名
FIELD = {
    "SubtitlesBorderStyle": "titleRes",
    "AudioChannels": "title",
    "MPVProfile": "displayNameRes",
    "Debanding": "titleRes",
    "SingleActionGesture": "titleRes",
    "PlayerOrientation": "titleRes",
}


def ensure_import(src, imp, *anchors):
    """在首个能匹配到的 anchor 之前插入 import；都匹配不到就不动。"""
    if imp in src:
        return src
    for a in anchors:
        m = re.search(a, src, re.M)
        if m:
            return src[:m.start()] + imp + "\n" + src[m.start():]
    return src


def main():
    total = 0
    for dp, _, fs in os.walk(JAVA):
        for f in fs:
            if not f.endswith(".kt"):
                continue
            p = os.path.join(dp, f)
            src = open(p, encoding="utf-8").read()
            orig = src
            n = 0
            for enum, field in FIELD.items():
                line = f"values = {enum}.entries,"
                if line not in src:
                    continue
                # 保留原始缩进：只替换行本身，追加一行同缩进的 valueToText
                src, cnt = re.subn(
                    r"( *)" + re.escape(line),
                    lambda m: (
                        f"{m.group(1)}{line}\n"
                        f"{m.group(1)}valueToText = {{ value -> AnnotatedString(stringResource(value.{field})) }},"
                    ),
                    src,
                )
                n += cnt
            if src == orig:
                continue
            src = ensure_import(
                src,
                "import androidx.compose.ui.text.AnnotatedString",
                r"^import androidx\.compose\.ui\.Modifier",
                r"^import androidx\.compose\.runtime\.",
            )
            src = ensure_import(
                src,
                "import androidx.compose.ui.res.stringResource",
                r"^import androidx\.compose\.ui\.Modifier",
            )
            open(p, "w", encoding="utf-8", newline="\n").write(src)
            print(f"  {os.path.relpath(p, JAVA)}: {n} 处")
            total += n
    print(f"合计 {total} 处")


if __name__ == "__main__":
    main()
