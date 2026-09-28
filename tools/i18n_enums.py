#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把枚举里硬编码的显示名换成字符串资源。

背景：FilterPreset.displayName/description、MPVProfile.displayName、SeekbarStyle
直接以英文字面量显示（设置页与播放面板），中文界面上就会露出英文。
本脚本负责改写 PlayerEnums.kt 的枚举体，并向两个 strings.xml 注入资源。
调用点（VideoSettingsFilterPresetsCard / DecoderPreferencesScreen / 设置页）
由脚本外的手工编辑同步，因为各自需要的写法不同。
"""
import os
import re

ROOT = r"D:\project\mpvEx-master\app\src\main"
ENUM_KT = os.path.join(ROOT, "java", "app", "marlboroadvance", "mpvex", "ui", "player", "PlayerEnums.kt")
EN_XML = os.path.join(ROOT, "res", "values", "strings.xml")
ZH_XML = os.path.join(ROOT, "res", "values-zh-rCN", "strings.xml")

# ── 滤镜预设：英文名 -> (键后缀, 中文名, 英文描述, 中文描述) ──
FILTER_PRESETS = [
    ("None",       "none",       "无",       "Default settings with no adjustments",              "不做任何调整的默认设置"),
    ("Vivid",      "vivid",      "鲜艳",     "Enhanced colors with crisp details",                "色彩增强，细节更锐利"),
    ("Warm Tone",  "warm_tone",  "暖色调",   "Warmer colors with golden tint",                    "偏暖色，带金色滤镜"),
    ("Cool Tone",  "cool_tone",  "冷色调",   "Cooler colors with blue tint",                      "偏冷色，带蓝色滤镜"),
    ("Soft Pastel", "soft_pastel", "柔和粉彩", "Soft, muted colors with gentle look",             "柔和淡雅的色彩"),
    ("Cinematic",  "cinematic",  "电影感",   "Film-like color grading with depth",                "胶片质感调色，更有层次"),
    ("Dramatic",   "dramatic",   "戏剧化",   "High contrast dramatic look",                       "高对比度的戏剧化效果"),
    ("Night Mode", "night_mode", "夜间模式", "Reduced brightness for dark environments",          "降低亮度，适合暗环境"),
    ("Nostalgic",  "nostalgic",  "怀旧",     "Vintage film look with soft focus",                 "复古胶片质感，柔焦"),
    ("Ghibli Style", "ghibli",   "吉卜力风", "Soft, dreamy anime colors",                         "柔和梦幻的动画色彩"),
    ("Neon Pop",   "neon_pop",   "霓虹",     "Vibrant neon-like colors with edge",                "鲜艳的霓虹感色彩，边缘锐利"),
    ("Deep Black", "deep_black", "深邃黑",   "Enhanced blacks for OLED displays",                 "强化黑色，适合 OLED 屏幕"),
]

# ── 播放配置：(常量名, 英文显示名, 键后缀, 中文) ──
MPV_PROFILES = [
    ("Fast",       "Fast",         "fast",         "快速"),
    ("Default",    "Default",      "default",      "默认"),
    ("HighQuality", "High Quality", "high_quality", "高质量"),
    ("GpuHQ",      "GPU HQ",       "gpu_hq",       "GPU 高画质"),
    ("LowLatency", "Low Latency",  "low_latency",  "低延迟"),
    ("SwFast",     "SW Fast",      "sw_fast",      "软件快速"),
]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")


def add_strings(pairs):
    """pairs: [(key, en, zh)]，返回实际新增条数"""
    added = 0
    for path, is_zh in ((EN_XML, False), (ZH_XML, True)):
        src = open(path, encoding="utf-8").read()
        lines = [f'  <string name="{k}">{esc(zh if is_zh else en)}</string>'
                 for k, en, zh in pairs if f'name="{k}"' not in src]
        if lines:
            idx = src.rstrip().rfind("</resources>")
            src = src[:idx] + "\n".join(lines) + "\n" + src[idx:]
            open(path, "w", encoding="utf-8", newline="\n").write(src)
            added += len(lines)
    return added


def main():
    src = open(ENUM_KT, encoding="utf-8").read()

    # ── 1. FilterPreset：displayName/description -> displayNameRes/descriptionRes ──
    fp_pairs = []
    for en_name, slug, zh_name, en_desc, zh_desc in FILTER_PRESETS:
        k1, k2 = f"i18n_fp_{slug}", f"i18n_fp_{slug}_desc"
        fp_pairs += [(k1, en_name, zh_name), (k2, en_desc, zh_desc)]
        old = f'displayName = "{en_name}",\n    description = "{en_desc}",'
        new = f'displayNameRes = R.string.{k1},\n    descriptionRes = R.string.{k2},'
        if old not in src:
            print(f"  !! 未匹配 FilterPreset 条目: {en_name}")
        src = src.replace(old, new)

    src = src.replace(
        "enum class FilterPreset(\n  val displayName: String,\n  val description: String,",
        "enum class FilterPreset(\n  @StringRes val displayNameRes: Int,\n  @StringRes val descriptionRes: Int,",
    )

    # ── 2. MPVProfile：displayName -> displayNameRes ──
    # 注意常量名是驼峰（HighQuality / GpuHQ / LowLatency / SwFast），
    # 不能用英文显示名首词去拼，必须显式给出常量名。
    mp_pairs = []
    for const, en_name, slug, zh_name in MPV_PROFILES:
        k = f"i18n_mpv_{slug}"
        mp_pairs.append((k, en_name, zh_name))
        old = f'{const}("{en_name}",'
        new = f'{const}(R.string.{k},'
        if old not in src:
            print(f"  !! 未匹配 MPVProfile 条目: {const}")
        src = src.replace(old, new)

    src = src.replace(
        "enum class MPVProfile(\n  val displayName: String,\n  val value: String,",
        "enum class MPVProfile(\n  @StringRes val displayNameRes: Int,\n  val value: String,",
    )
    src = src.replace("override fun toString(): String = displayName", "override fun toString(): String = name")

    open(ENUM_KT, "w", encoding="utf-8", newline="\n").write(src)

    n = add_strings(fp_pairs + mp_pairs)
    print(f"PlayerEnums.kt 已改写；strings.xml 新增 {n} 条资源")

    # 校验
    body = open(ENUM_KT, encoding="utf-8").read()
    left = re.findall(r'\bdisplayName\b|\bdescription\b', body)
    print(f"剩余 displayName/description 标识符: {len(left)}")
    print(f"displayNameRes 出现: {body.count('displayNameRes')}, mpv 资源: {body.count('i18n_mpv_')}")


if __name__ == "__main__":
    main()
