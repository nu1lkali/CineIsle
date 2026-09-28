# -*- coding: utf-8 -*-
"""修复 i18n 脚本重复注入 valueToText 的问题。

同一处 ListPreference 被写了两个 valueToText= 参数（Kotlin 直接编译报错），
保留 `value ->` 版本，删掉 `context.getString(it.` 版本。
"""
import io
import os
import re

ROOT = r"D:\project\mpvEx-master\app\src\main\java"

PAT_DUP = re.compile(r"valueToText\s*=\s*\{\s*AnnotatedString\(context\.getString\(it\.")

total = 0
for dirpath, _dirnames, filenames in os.walk(ROOT):
    for fn in filenames:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        with io.open(path, encoding="utf-8") as f:
            lines = f.read().split("\n")

        dup_idx = [i for i, l in enumerate(lines) if PAT_DUP.search(l)]
        if not dup_idx:
            continue

        remove = set()
        for i in dup_idx:
            # 邻近 6 行内还有别的 valueToText 参数 -> 属于重复
            lo, hi = max(0, i - 6), min(len(lines), i + 7)
            others = [j for j in range(lo, hi) if j != i and "valueToText" in lines[j]]
            if others:
                remove.add(i)

        if not remove:
            continue

        kept = [l for i, l in enumerate(lines) if i not in remove]
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(kept))
        total += len(remove)
        print("fixed %d line(s): %s" % (len(remove), path))

print("total removed:", total)
