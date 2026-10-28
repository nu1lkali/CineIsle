# -*- coding: utf-8 -*-
"""设置搜索索引 + 行锚点生成器（v172 / v173）。

用法（仓库根目录）：
    python tools/gen_pref_index.py

做三件事：
  1. 扫描各设置页里的 `title =` 赋值点，给那一行插上
     `modifier = Modifier.settingsAnchor("<锚点>")`（已有则跳过，脚本可重复跑）；
  2. 在每张设置页的 LazyColumn 上接 `listState` + `SettingsSpotlightReveal(...)`，
     让「搜索点进来」能滚到目标行（已接则跳过）；
  3. 生成 `SearchablePreferenceIndex.kt`，每条带 `anchor`，与第 1 步逐字对应。

为什么自动化：设置项分散在十多张页面里，标题有的走 string 资源、有的是中文字面量，
手写索引必然漏；「锚点」更是索引与 UI 两侧必须逐字一致，手写迟早对不上。
"""
import os
import re

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREFS = os.path.join(BASE, "app/src/main/java/app/marlboroadvance/mpvex/ui/preferences")
OUT = os.path.join(PREFS, "SearchablePreferenceIndex.kt")

# (文件名, Screen 对象, 默认分类, 页面级别名, 是否给 LazyColumn 接定位)
SCREENS = [
    ("PreferencesScreen.kt", "PreferencesScreen", "设置首页",
     "设置 preferences settings", True),
    ("AppearancePreferencesScreen.kt", "AppearancePreferencesScreen", "外观",
     "外观 主题 theme appearance 深色 浅色 amoled 颜色 卡片", True),
    ("AppearanceDeepCustomScreen.kt", "AppearanceDeepCustomScreen", "外观深度定制",
     "外观 深度定制 卡片 圆角 字号 颜色 appearance custom", True),
    ("PlayerPreferencesScreen.kt", "PlayerPreferencesScreen", "播放器",
     "播放器 player 播放 playback", True),
    ("PlayerControlsPreferencesScreen.kt", "PlayerControlsPreferencesScreen", "控件布局",
     "控件 布局 按钮 layout controls buttons 进度条", True),
    ("GesturePreferencesScreen.kt", "GesturePreferencesScreen", "手势",
     "手势 gesture 双击 滑动 音量 亮度", True),
    ("DanmakuSettingsScreen.kt", "DanmakuSettingsScreen", "弹幕",
     "弹幕 danmaku 弹幕源 弹幕库", True),
    ("GsyPreferencesScreen.kt", "GsyPreferencesScreen", "GSY 播放器",
     "GSY 播放器 内核 渲染 gsy player", True),
    ("DislikePreferencesScreen.kt", "DislikePreferencesScreen", "不喜欢",
     "不喜欢 dislike 标记 屏蔽", True),
    ("FoldersPreferencesScreen.kt", "FoldersPreferencesScreen", "文件夹",
     "文件夹 目录 folders 黑名单", False),
    ("DecoderPreferencesScreen.kt", "DecoderPreferencesScreen", "解码器",
     "解码 渲染 decoder 硬解 hw 滤镜 shader", True),
    ("SubtitlesPreferencesScreen.kt", "SubtitlesPreferencesScreen", "字幕",
     "字幕 subtitle ass 字体 语言", True),
    ("AudioPreferencesScreen.kt", "AudioPreferencesScreen", "音频",
     "音频 audio 声道 音量 音轨", True),
    ("AdvancedPreferencesScreen.kt", "AdvancedPreferencesScreen", "高级",
     "高级 advanced 日志 缓存 备份 导入 导出 mpv 配置", True),
    ("AboutScreen.kt", "AboutScreen", "关于",
     "关于 about 版本 开源 licenses", False),
]

# 只有这些「行组件」能安全地加 `modifier =`（不含 TopAppBar 之类的页面标题）
ROW_COMPOSABLES = {
    "Preference",
    "SwitchPreference",
    "ListPreference",
    "SliderPreference",
    "TextFieldPreference",
    "MultiSelectListPreference",
    "TwoTargetSwitchPreference",
    "TwoTargetPreference",
    "TwoTargetIconButtonPreference",
    "ToggleRow",
    "TickedSlider",
    "PreferenceCategoryWithEditButton",
}

# 弹窗标题不是配置项，不进索引（「导出完成」「关闭播放器按钮？」之类）
DIALOG_COMPOSABLES = {
    "AlertDialog",
    "ConfirmDialog",
    "IntervalInputDialog",
    "PinVerifyDialog",
    "PinSetupDialog",
    "PinChangeDialog",
    "PatternVerifyDialog",
    "PatternSetupDialog",
    "PatternChangeDialog",
    "UnlockMethodDialog",
}

RSTR = re.compile(r"R\.string\.(\w+)")
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
SECT = re.compile(
    r'PreferenceSectionHeader\(\s*title\s*=\s*(?:"([^"]+)"|stringResource\(\s*R\.string\.(\w+))'
)
TITLE_LINE = re.compile(r"^(\s*)title\s*=")
WIN_TITLE = 4
WIN_SUMMARY = 4


def mask(text):
    """把字符串内容与行注释抹成空格（保留引号与换行），只为了做括号配对。"""
    buf = list(text)
    n = len(text)
    i = 0
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            if c == "\\":
                if i + 1 < n and text[i + 1] != "\n":
                    buf[i + 1] = " "
                buf[i] = " "
                i += 2
                continue
            if c == '"':
                in_str = False
            elif c != "\n":
                buf[i] = " "
            i += 1
            continue
        if c == '"':
            in_str = True
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                buf[k] = " "
            i = j
            continue
        i += 1
    return "".join(buf).split("\n")


def enclosing_call(masked, idx):
    """从 `title =` 那一行反向做括号配对，找出包住它的调用名。"""
    depth = 0
    for j in range(idx, -1, -1):
        s = masked[j]
        if j == idx:
            k = s.find("title")
            if k < 0:
                return None, -1
            s = s[:k]
        for p in range(len(s) - 1, -1, -1):
            ch = s[p]
            if ch == ")":
                depth += 1
            elif ch == "(":
                if depth == 0:
                    m = re.search(r"([A-Za-z_]\w*)\s*$", s[:p])
                    return (m.group(1) if m else None), j
                depth -= 1
    return None, -1


def pick_text(lines, start, span):
    chunk = []
    for i in range(start, min(start + span, len(lines))):
        chunk.append(lines[i])
        joined = "\n".join(chunk)
        m = RSTR.search(joined)
        if m:
            return "res", m.group(1)
        for lm in LIT.finditer(joined):
            val = lm.group(1)
            if val and not val.startswith("\\"):
                if re.search(r"[\u4e00-\u9fffA-Za-z]", val) and len(val) > 1:
                    return "lit", val
    return None, None


def kstr(s):
    if s is None:
        return None
    s = s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return s.replace("\n", " ")


def scan_screen(path, default_cat, reveal):
    """返回 (rows, insertions)：rows 带锚点信息，insertions = [(行号, 文本)]。"""
    text = open(path, encoding="utf-8").read()
    lines = text.split("\n")
    masked = mask(text)

    cur = default_cat
    sect_at = {}
    for idx, ln in enumerate(lines):
        m = SECT.search(ln)
        if m:
            cur = m.group(1) or m.group(2)
        sect_at[idx] = cur

    rows = []
    insertions = []
    used = set()

    for idx, ln in enumerate(lines):
        m = TITLE_LINE.match(ln)
        if not m:
            continue
        kind, val = pick_text(lines, idx, WIN_TITLE)
        if not val:
            continue
        name, open_idx = enclosing_call(masked, idx)

        # 弹窗标题不是配置项：既不注入锚点，也不进索引
        if name in DIALOG_COMPOSABLES:
            continue

        anchor = None
        if reveal and name in ROW_COMPOSABLES:
            # 幂等：这一行已经挂过锚点就沿用（重跑脚本不再插第二行）
            existing = None
            has_modifier = False
            for j in range(open_idx + 1, idx):
                sm = re.search(r'settingsAnchor\("((?:[^"\\]|\\.)*)"\)', lines[j])
                if sm:
                    existing = sm.group(1)
                if re.match(r"^\s*modifier\s*=", lines[j]):
                    has_modifier = True
            if existing:
                anchor = existing
                used.add(anchor)
            elif not has_modifier:
                base = val
                anchor = base
                n = 2
                while anchor in used:
                    anchor = f"{base}#{n}"
                    n += 1
                used.add(anchor)
                indent = m.group(1)
                insertions.append(
                    (idx, f'{indent}modifier = Modifier.settingsAnchor("{kstr(anchor)}"),')
                )

        sum_kind, sum_val = None, None
        for j in range(idx, min(idx + 16, len(lines))):
            if re.match(r"^\s*(summary|subtitle)\s*=", lines[j]):
                sum_kind, sum_val = pick_text(lines, j, WIN_SUMMARY)
                break

        rows.append({
            "titleKind": kind,
            "title": val,
            "sumKind": sum_kind,
            "sum": sum_val,
            "category": sect_at.get(idx) or default_cat,
            "anchor": anchor,
        })

    # 从后往前插，避免行号漂移
    for idx, new_line in sorted(insertions, key=lambda x: -x[0]):
        lines.insert(idx, new_line)

    # 给 LazyColumn 接上 listState + 定位副作用（幂等）
    if reveal and "rememberLazyListState()" not in "\n".join(lines):
        out = []
        inserted = False
        for ln in lines:
            if not inserted and re.match(r"^\s*LazyColumn\(\s*$", ln):
                indent = ln[: len(ln) - len(ln.lstrip())]
                out.append(f"{indent}val listState = rememberLazyListState()")
                out.append(f"{indent}SettingsSpotlightReveal(listState, {SCREEN_OF[path]})")
                out.append(ln)
                out.append(f"{indent}  state = listState,")
                inserted = True
                continue
            out.append(ln)
        lines = out
        if inserted:
            # 补 import（放在 LazyColumn 的 import 后面）
            for i, ln in enumerate(lines):
                if re.match(r"^import androidx\.compose\.foundation\.lazy\.LazyColumn$", ln):
                    lines.insert(i + 1, "import androidx.compose.foundation.lazy.rememberLazyListState")
                    break

    open(path, "w", encoding="utf-8").write("\n".join(lines))
    return rows, len(insertions)


SCREEN_OF = {}


HEADER = '''package app.marlboroadvance.mpvex.ui.preferences

import app.marlboroadvance.mpvex.R

/**
 * 设置搜索索引（v172/v173）—— 由 `tools/gen_pref_index.py` 从各设置页源码抽取，**不要手改**。
 *
 * 为什么需要这一份：手写索引 [SearchablePreferences] 每张页面只登记了「首页那个入口」，
 * 而 `PreferencesScreen.kt` 里内联着上百个配置项（Emby 推荐 / 封面合成 / 随机播放 /
 * 网络浏览 / 数据缓存 / 应用锁 …），标题用的是**中文字面量**而不是 string 资源 ——
 * 两边都对不上，结果就是「很多配置项都搜索不出来」。
 *
 * 抽取口径：源码里的 `title =` 赋值点（`SwitchPreference` / `ListPreference` /
 * `SliderPreference` / `Preference` 与 `ToggleRow` 之类自定义行都算），副标题取紧随
 * 其后的 `summary =` / `subtitle =`，分类取最近一个 `PreferenceSectionHeader`。
 * `anchor` 与那一行上的 `Modifier.settingsAnchor(...)` 逐字对应 —— 点搜索结果时
 * 目标页靠它滚过去并闪一下（见 [SettingsSpotlight]）。**新增设置项后重跑脚本即可。**
 *
 * ⚠️ 与手写索引**并存**：手写那份带英文关键词（amoled / vulkan / danmaku …），是搜英文的
 *    唯一来源；合并时按 `titleRes` / `title` 去重，手写优先。
 */
internal val generatedSearchablePreferences: List<SearchablePreference> = buildList {
'''


def main():
    for fname, screen, _c, _a, _r in SCREENS:
        SCREEN_OF[os.path.join(PREFS, fname)] = screen

    all_items = []
    for fname, screen, cat, aliases, reveal in SCREENS:
        path = os.path.join(PREFS, fname)
        if not os.path.exists(path):
            print("MISSING", fname)
            continue
        rows, injected = scan_screen(path, cat, reveal)
        for r in rows:
            r["screen"] = screen
            r["aliases"] = aliases
        print(f"{fname:42s} rows={len(rows):3d} anchors+={injected:3d}")
        all_items.extend(rows)

    seen = set()
    uniq = []
    for it in all_items:
        key = (it["titleKind"], it["title"], it["screen"])
        if key in seen:
            continue
        seen.add(key)
        uniq.append(it)

    lines = [HEADER.rstrip("\n")]
    alias_done = set()
    for it in uniq:
        first = it["screen"] not in alias_done
        alias_done.add(it["screen"])
        args = []
        if it["titleKind"] == "res":
            args.append(f"titleRes = R.string.{it['title']},")
        else:
            args.append(f'title = "{kstr(it["title"])}",')
        if it["sumKind"] == "res":
            args.append(f"summaryRes = R.string.{it['sum']},")
        elif it["sumKind"] == "lit":
            args.append(f'summary = "{kstr(it["sum"])}",')
        if first:
            kw = ", ".join(f'"{kstr(a)}"' for a in it["aliases"].split() if a)
            args.append(f"screenKeywords = listOf({kw}),")
        if it["anchor"]:
            args.append(f'anchor = "{kstr(it["anchor"])}",')
        args.append(f'category = "{kstr(it["category"])}",')
        args.append(f"screen = {it['screen']},")
        body = "".join(f"\n        {a}" for a in args)
        lines.append("    add(SearchablePreference(" + body + "\n    ))")
    lines.append("}")
    lines.append("")

    open(OUT, "w", encoding="utf-8").write("\n".join(lines))
    print(f"total {len(all_items)} unique {len(uniq)} "
          f"anchored {sum(1 for i in uniq if i['anchor'])}")


main()
