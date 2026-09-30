"""把播放内核调用从 MPVLib 机械替换为 PlayerLib（门面层）。

规则：
1. 只替换 `MPVLib.<小写成员>`（command / getProperty* / setProperty* / propInt ...），
   保留 `MPVLib.MpvFormat` / `MPVLib.MpvEvent` / `MPVLib.EventObserver` —— 这些是类型引用，
   PlayerLib 直接复用它们，不需要另造一套。
2. 跳过两个「只在 mpv 内核下才会被创建」的文件：MPVView.kt（mpv 的渲染视图本体）与
   MediaPlaybackService.kt（后台播放，Exo 下不支持、入口会被隐藏）。
3. 跳过 engine 包自身（PlayerLib / ExoBackend 里本来就要直接调 MPVLib）。
4. 补 import；原有的 `import is.xyz.mpv.MPVLib` 保留（MpvFormat 等还要用）。
"""

import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/java"
SKIP_NAMES = {"MPVView.kt", "MediaPlaybackService.kt"}
SKIP_DIRS = {"engine"}

IMPORT_LINE = "import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib\n"
PATTERN = re.compile(r"MPVLib\.(?=[a-z])")

changed = []
for path in ROOT.rglob("*.kt"):
    if path.name in SKIP_NAMES:
        continue
    if SKIP_DIRS & set(path.parts):
        continue
    text = path.read_text(encoding="utf-8")
    if "MPVLib." not in text:
        continue
    new_text, n = PATTERN.subn("PlayerLib.", text)
    if n == 0:
        continue
    if IMPORT_LINE not in new_text:
        lines = new_text.split("\n")
        # 插到 package 行之后
        idx = next(i for i, l in enumerate(lines) if l.startswith("package "))
        lines.insert(idx + 1, IMPORT_LINE.rstrip("\n"))
        new_text = "\n".join(lines)
    path.write_text(new_text, encoding="utf-8")
    changed.append((str(path.relative_to(ROOT)), n))

for name, n in sorted(changed):
    print(f"{n:4d}  {name}")
print(f"TOTAL files={len(changed)} replacements={sum(n for _, n in changed)}")
