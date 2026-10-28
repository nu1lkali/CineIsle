# -*- coding: utf-8 -*-
"""设置搜索索引生成器。

用法（在仓库根目录）：
    python tools/gen_pref_index.py

它扫描 `app/src/main/java/app/marlboroadvance/mpvex/ui/preferences/*.kt` 里所有
`title =` 赋值点，抽取「标题 / 副标题 / 所属分区 / 所属页面」，重新生成
`SearchablePreferenceIndex.kt`。

为什么要自动抽取：设置项分散在十多张页面里，标题有的走 string 资源、有的是
中文字面量（`PreferencesScreen.kt` 里的 Emby / 网络浏览 / 数据缓存 / 应用锁全是指面量），
手写索引必然漏。新增设置项后重跑本脚本即可，不用再手工维护一张清单。
"""
import os
import re

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREFS = os.path.join(
    BASE, "app/src/main/java/app/marlboroadvance/mpvex/ui/preferences"
)
OUT = os.path.join(PREFS, "SearchablePreferenceIndex.kt")

# (文件名, Screen 对象, 默认分类, 页面级别名)
SCREENS = [
    ("PreferencesScreen.kt", "PreferencesScreen", "设置首页",
     "设置 preferences settings"),
    ("AppearancePreferencesScreen.kt", "AppearancePreferencesScreen", "外观",
     "外观 主题 theme appearance 深色 浅色 amoled 颜色 卡片"),
    ("AppearanceDeepCustomScreen.kt", "AppearanceDeepCustomScreen", "外观深度定制",
     "外观 深度定制 卡片 圆角 字号 颜色 appearance custom"),
    ("PlayerPreferencesScreen.kt", "PlayerPreferencesScreen", "播放器",
     "播放器 player 播放 playback"),
    ("PlayerControlsPreferencesScreen.kt", "PlayerControlsPreferencesScreen", "控件布局",
     "控件 布局 按钮 layout controls buttons 进度条"),
    ("GesturePreferencesScreen.kt", "GesturePreferencesScreen", "手势",
     "手势 gesture 双击 滑动 音量 亮度"),
    ("DanmakuSettingsScreen.kt", "DanmakuSettingsScreen", "弹幕",
     "弹幕 danmaku 弹幕源 弹幕库"),
    ("GsyPreferencesScreen.kt", "GsyPreferencesScreen", "GSY 播放器",
     "GSY 播放器 内核 渲染 gsy player"),
    ("DislikePreferencesScreen.kt", "DislikePreferencesScreen", "不喜欢",
     "不喜欢 dislike 标记 屏蔽"),
    ("FoldersPreferencesScreen.kt", "FoldersPreferencesScreen", "文件夹",
     "文件夹 目录 folders 黑名单"),
    ("DecoderPreferencesScreen.kt", "DecoderPreferencesScreen", "解码器",
     "解码 渲染 decoder 硬解 hw 滤镜 shader"),
    ("SubtitlesPreferencesScreen.kt", "SubtitlesPreferencesScreen", "字幕",
     "字幕 subtitle ass 字体 语言"),
    ("AudioPreferencesScreen.kt", "AudioPreferencesScreen", "音频",
     "音频 audio 声道 音量 音轨"),
    ("AdvancedPreferencesScreen.kt", "AdvancedPreferencesScreen", "高级",
     "高级 advanced 日志 缓存 备份 导入 导出 mpv 配置"),
    ("AboutScreen.kt", "AboutScreen", "关于",
     "关于 about 版本 开源 licenses"),
]

RSTR = re.compile(r"R\.string\.(\w+)")
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
SECT = re.compile(
    r'PreferenceSectionHeader\(\s*title\s*=\s*(?:"([^"]+)"|stringResource\(\s*R\.string\.(\w+))'
)
WIN_TITLE = 4
WIN_SUMMARY = 4


def pick_text(lines, start, span):
    """在 [start, start+span) 行里取第一个 R.string 名或第一个字符串字面量。"""
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


def extract(path, default_cat, aliases, screen):
    lines = open(path, encoding="utf-8").read().split("\n")
    cur = default_cat
    sect_at = {}
    for idx, ln in enumerate(lines):
        m = SECT.search(ln)
        if m:
            cur = m.group(1) or m.group(2)
        sect_at[idx] = cur

    out = []
    for idx, ln in enumerate(lines):
        if not re.match(r"^\s*title\s*=", ln):
            continue
        kind, val = pick_text(lines, idx, WIN_TITLE)
        if not val:
            continue
        sum_kind, sum_val = None, None
        for j in range(idx, min(idx + 16, len(lines))):
            if re.match(r"^\s*(summary|subtitle)\s*=", lines[j]):
                sum_kind, sum_val = pick_text(lines, j, WIN_SUMMARY)
                break
        out.append({
            "titleKind": kind,
            "title": val,
            "sumKind": sum_kind,
            "sum": sum_val,
            "category": sect_at.get(idx) or default_cat,
            "screen": screen,
            "aliases": aliases,
        })
    return out


def kstr(s):
    if s is None:
        return None
    s = s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return s.replace("\n", " ")


HEADER = '''package app.marlboroadvance.mpvex.ui.preferences

import app.marlboroadvance.mpvex.R

/**
 * 设置搜索索引（v172）—— 由 `tools/gen_pref_index.py` 从各设置页源码抽取，**不要手改**。
 *
 * 为什么需要这一份：手写索引 [SearchablePreferences] 每张页面只登记了「首页那个入口」，
 * 而 `PreferencesScreen.kt` 里内联着上百个配置项（Emby 推荐 / 封面合成 / 随机播放 /
 * 网络浏览 / 数据缓存 / 应用锁 …），标题用的是**中文字面量**而不是 string 资源 ——
 * 两边都对不上，结果就是「很多配置项都搜索不出来」。
 *
 * 抽取口径：源码里的 `title =` 赋值点（`SwitchPreference` / `ListPreference` /
 * `SliderPreference` / `Preference` 与 `ToggleRow` 之类自定义行都算），副标题取紧随
 * 其后的 `summary =` / `subtitle =`，分类取最近一个 `PreferenceSectionHeader`。
 * 新增设置项后重跑脚本即可。
 *
 * ⚠️ 与手写索引**并存**：手写那份带英文关键词（amoled / vulkan / danmaku …），是搜英文的
 *    唯一来源；合并时按 `titleRes` / `title` 去重，手写优先。
 */
internal val generatedSearchablePreferences: List<SearchablePreference> = buildList {
'''


def main():
    all_items = []
    for fname, screen, cat, aliases in SCREENS:
        path = os.path.join(PREFS, fname)
        if not os.path.exists(path):
            print("MISSING", fname)
            continue
        items = extract(path, cat, aliases, screen)
        print(f"{fname:42s} -> {len(items):3d} items")
        all_items.extend(items)

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
        args.append(f'category = "{kstr(it["category"])}",')
        args.append(f"screen = {it['screen']},")
        body = "".join(f"\n        {a}" for a in args)
        lines.append("    add(SearchablePreference(" + body + "\n    ))")
    lines.append("}")
    lines.append("")

    open(OUT, "w", encoding="utf-8").write("\n".join(lines))
    print(f"total {len(all_items)} unique {len(uniq)} -> {OUT}")


main()
