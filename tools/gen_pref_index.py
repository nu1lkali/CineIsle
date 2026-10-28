# -*- coding: utf-8 -*-
"""设置搜索索引 + 行定位锚点生成器（v172 初版 / v174 重写）。

用法（仓库根目录）：
    python tools/gen_pref_index.py

做三件事：
  1. 扫描各设置页里的 `title =` 赋值点，给那一行插上
     `modifier = Modifier.settingsAnchor(...)`（已有则跳过，脚本可重复跑）；
  2. 在每张带主 LazyColumn 的设置页上接 `listState` + `SettingsSpotlightReveal(...)`，
     让「搜索点进来」能滚到目标行（已接则跳过）；
  3. 生成 `SearchablePreferenceIndex.kt`。

## v174 的关键改动：锚点不再是「另一套标识」

v173 的锚点是脚本另起的名字（`pref_xxx` 这种资源名，或中文字面量），索引里存一份、
行上挂一份。两边一旦不同步就**静默**失效 —— 只要索引条目在手写清单里也有一份
（手写那份没有 `anchor` 字段），合并时锚点就丢了，点进去只到页顶。

现在锚点 = **那一行自己显示的文字**：资源标题喂 `R.string.x`（运行时解析成字符串），
字面量标题喂字面量。索引条目也只记 `titleRes` / `title`，不再有第三套键。
两侧都从同一个表达式派生，所以「索引能选中」⇒「页面上那一行能定位」。

推论：**索引里只放能定位的行**（下面 `anchor_ok` 过滤）。搜得到却点过去没反应，
比搜不到更让人困惑。目前被过滤掉的分三类，都打在 stdout 里：
  - 行上已经有自定义 `modifier =`（会改成往后追加 `.settingsAnchor(...)`，不再过滤）；
  - 标题是模板串（`"第$n集"` 这种，运行时才知道长什么样，两边无法对齐）；
  - 标题是变量（`title = item.title`），索引里本来就没有文字可用。
"""
import os
import re

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREFS = os.path.join(BASE, "app/src/main/java/app/marlboroadvance/mpvex/ui/preferences")
OUT = os.path.join(PREFS, "SearchablePreferenceIndex.kt")

# (文件名, Screen 对象, 默认分类, 页面级别名, 注入锚点, 接 listState)
SCREENS = [
    ("PreferencesScreen.kt", "PreferencesScreen", "设置首页",
     "设置 preferences settings", True, True),
    ("AppearancePreferencesScreen.kt", "AppearancePreferencesScreen", "外观",
     "外观 主题 theme appearance 深色 浅色 amoled 颜色 卡片", True, True),
    ("AppearanceDeepCustomScreen.kt", "AppearanceDeepCustomScreen", "外观深度定制",
     "外观 深度定制 卡片 圆角 字号 颜色 appearance custom", True, True),
    ("PlayerPreferencesScreen.kt", "PlayerPreferencesScreen", "播放器",
     "播放器 player 播放 playback", True, True),
    ("PlayerControlsPreferencesScreen.kt", "PlayerControlsPreferencesScreen", "控件布局",
     "控件 布局 按钮 layout controls buttons 进度条", True, True),
    ("GesturePreferencesScreen.kt", "GesturePreferencesScreen", "手势",
     "手势 gesture 双击 滑动 音量 亮度", True, True),
    ("DanmakuSettingsScreen.kt", "DanmakuSettingsScreen", "弹幕",
     "弹幕 danmaku 弹幕源 弹幕库", True, True),
    ("GsyPreferencesScreen.kt", "GsyPreferencesScreen", "GSY 播放器",
     "GSY 播放器 内核 渲染 gsy player", True, True),
    ("DislikePreferencesScreen.kt", "DislikePreferencesScreen", "不喜欢",
     "不喜欢 dislike 标记 屏蔽", True, True),
    # 没有主 LazyColumn（卡片里嵌小 LazyColumn）⇒ 定位副作用手写在页面里
    ("FoldersPreferencesScreen.kt", "FoldersPreferencesScreen", "文件夹",
     "文件夹 目录 folders 黑名单", True, False),
    ("DecoderPreferencesScreen.kt", "DecoderPreferencesScreen", "解码器",
     "解码 渲染 decoder 硬解 hw 滤镜 shader", True, True),
    ("SubtitlesPreferencesScreen.kt", "SubtitlesPreferencesScreen", "字幕",
     "字幕 subtitle ass 字体 语言", True, True),
    ("AudioPreferencesScreen.kt", "AudioPreferencesScreen", "音频",
     "音频 audio 声道 音量 音轨", True, True),
    ("AdvancedPreferencesScreen.kt", "AdvancedPreferencesScreen", "高级",
     "高级 advanced 日志 缓存 备份 导入 导出 mpv 配置", True, True),
    # 普通 Column + verticalScroll ⇒ 所有行天然在组合里，定位副作用手写在页面里
    ("AboutScreen.kt", "AboutScreen", "关于",
     "关于 about 版本 开源 licenses", True, False),
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
MOD_LINE = re.compile(r"^\s*modifier\s*=")
WIN_SUMMARY = 4

OPEN = "([{"
CLOSE = ")]}"


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
    """从 `title =` 那一行反向做括号配对，找出包住它的调用名与左括号所在行。"""
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


def expr_end(masked, line, col):
    """从 (line, col) 的表达式起点往后找「同一层的那个逗号」，返回 (行, 列)。

    用于把 `title =` / `modifier =` 的值范围切准 —— v173 用的是「往后看 N 行里第一个
    资源名」，标题写成变量时会误抓下面 `summary` 的资源，索引里就出现一条永远对不上的
    标题（点进去没反应）。
    """
    depth = 0
    i, j = line, col
    while i < len(masked):
        s = masked[i]
        while j < len(s):
            ch = s[j]
            if ch in OPEN:
                depth += 1
            elif ch in CLOSE:
                if depth == 0:
                    return i, j
                depth -= 1
            elif ch == "," and depth == 0:
                return i, j
            j += 1
        i += 1
        j = 0
    return len(masked) - 1, len(masked[-1])


def res_refs(masked, line, col, end_line, end_col):
    """标题表达式里出现的所有 string 资源（保持顺序去重）。

    三元表达式（`title = if (flag) R.string.a else R.string.b`）会返回两个 ——
    这一行按状态显示不同文案，两条都得登记，否则非默认状态那一条定位不到。
    """
    chunk = []
    for i in range(line, min(end_line + 1, len(masked))):
        seg = masked[i]
        if i == line:
            seg = seg[col:]
        if i == end_line:
            seg = seg[: end_col - (col if i == line else 0)]
        chunk.append(seg)
    return list(dict.fromkeys(RSTR.findall("\n".join(chunk))))


def pick_text(lines, line, col, end_line, end_col):
    """在 [line,col, end_line,end_col) 这段源码里取标题：优先 string 资源，其次字面量。"""
    chunk = []
    for i in range(line, min(end_line + 1, len(lines))):
        seg = lines[i]
        if i == line:
            seg = seg[col:]
        if i == end_line:
            seg = seg[: end_col - (col if i == line else 0)]
        chunk.append(seg)
    joined = "\n".join(chunk)
    m = RSTR.search(joined)
    if m:
        return "res", m.group(1)
    for lm in LIT.finditer(joined):
        val = lm.group(1)
        if val and len(val) > 1 and re.search(r"[\u4e00-\u9fffA-Za-z]", val):
            return "lit", val
    return None, None


def kstr(s):
    if s is None:
        return None
    s = s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return s.replace("\n", " ")


def anchor_arg(kind, val):
    """锚点参数：资源用 `R.string.x`（运行时解析成显示文字），字面量直接用。"""
    return f"R.string.{val}" if kind == "res" else f'"{kstr(val)}"'


# v173 注入的两种形态，跑脚本前一律先抹掉，再按新规则重注 —— 「先擦干净再画」
# 比「就地判断该不该改」可靠：旧文件里锚点是资源名，新规则要用资源引用，形态不同。
OLD_ANCHOR_LINE = re.compile(r'^\s*modifier\s*=\s*Modifier\.settingsAnchor\([^()]*\),\s*$')


def normalize(path):
    """擦掉上一轮注入的「整行 modifier」锚点，让脚本可以无脑重注。

    ⚠️ 只擦整行那种（`modifier = Modifier.settingsAnchor(...),`）。手写的、以及追加在
    `Modifier.xxx()` 链尾的锚点**保留** —— 扫描时会认得出来并沿用（见 scan_screen），
    这样「脚本管的」和「人管的」不会互相覆盖。
    """
    text = open(path, encoding="utf-8").read()
    lines = [ln for ln in text.split("\n") if not OLD_ANCHOR_LINE.match(ln)]
    open(path, "w", encoding="utf-8").write("\n".join(lines))


def scan_screen(path, default_cat, do_anchor, hook_list):
    if do_anchor:
        normalize(path)
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
    insertions = []   # [(行号, 文本)]  整行插入
    appends = []      # [(行号, 列, 文本)]  在既有 modifier 表达式尾部追加

    for idx, ln in enumerate(lines):
        m = TITLE_LINE.match(ln)
        if not m:
            continue
        name, open_idx = enclosing_call(masked, idx)
        head = re.match(r"^\s*title\s*=\s*", ln)
        t_start = head.end() if head else (ln.find("title") + len("title ="))
        t_end_line, t_end_col = expr_end(masked, idx, t_start)
        refs = res_refs(masked, idx, t_start, t_end_line, t_end_col)
        kind, val = pick_text(lines, idx, t_start, t_end_line, t_end_col)
        if not val:
            continue

        # 弹窗标题不是配置项：既不注入锚点，也不进索引
        if name in DIALOG_COMPOSABLES:
            continue

        # 模板串（含 `$`）：运行时才知道文字长什么样，两侧没法逐字对齐 ⇒ 不收
        if kind == "lit" and "$" in val:
            print(f"  SKIP(template) {os.path.basename(path)}:{idx + 1} {val}")
            continue

        # 这一行可能显示的文字：三元 / when 的标题（`if (c) A else B`）全都登记，
        # 运行时显示哪一句都能对上 —— 否则「居中双击」这种按状态换文案的行永远定位不到。
        titles = [("res", r) for r in refs] if refs else [(kind, val)]

        anchor_ok = False
        if do_anchor and name in ROW_COMPOSABLES:
            arg = ", ".join(anchor_arg(k, v) for k, v in titles)
            # 在这行的调用范围内找既有的 `modifier =`（参数顺序不定，title 之后也可能有）
            call_end_line, _ = expr_end(masked, open_idx, len(lines[open_idx]))
            mod_line = None
            for j in range(open_idx, min(call_end_line + 1, len(masked))):
                if MOD_LINE.match(masked[j]):
                    mod_line = j
                    break
            if mod_line is not None:
                mcol = masked[mod_line].find("=") + 1
                e_line, e_col = expr_end(masked, mod_line, mcol)
                span = "\n".join(lines[mod_line:e_line + 1])
                if "settingsAnchor(" in span:
                    # 已经挂过（上一轮追加的 / 手写的）⇒ 沿用，别再叠一层
                    got = ANCHOR_RX.search(span)
                    if got:
                        titles = ([("res", got.group(2))] if got.group(2)
                                  else [("lit", got.group(3))])
                    anchor_ok = True
                else:
                    # 已有 modifier（`.padding(...)` 之类）：在表达式尾部追加，别覆盖人家的
                    appends.append((e_line, e_col, f".settingsAnchor({arg})"))
                    anchor_ok = True
            else:
                indent = m.group(1)
                insertions.append((idx, f"{indent}modifier = Modifier.settingsAnchor({arg}),"))
                anchor_ok = True

        sum_kind, sum_val = None, None
        for j in range(idx, min(idx + 16, len(lines))):
            if re.match(r"^\s*(summary|subtitle)\s*=", lines[j]):
                scol = lines[j].find("=") + 1
                s_end_line, s_end_col = expr_end(masked, j, scol)
                sum_kind, sum_val = pick_text(lines, j, scol, s_end_line, s_end_col)
                break

        rows.append({
            "titles": titles,
            "sumKind": sum_kind,
            "sum": sum_val,
            "category": sect_at.get(idx) or default_cat,
            "anchored": anchor_ok,
            "line": idx + 1,
        })

    # 追加（同行多点要按列倒序）
    for e_line, e_col, add in sorted(appends, key=lambda x: (x[0], x[1]), reverse=True):
        s = lines[e_line]
        lines[e_line] = s[:e_col] + add + s[e_col:]

    # 从后往前插整行，避免行号漂移
    for idx, new_line in sorted(insertions, key=lambda x: -x[0]):
        lines.insert(idx, new_line)

    # 给主 LazyColumn 接上 listState + 定位副作用（幂等）
    if hook_list and "rememberLazyListState()" not in "\n".join(lines):
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
            for i, ln in enumerate(lines):
                if re.match(r"^import androidx\.compose\.foundation\.lazy\.LazyColumn$", ln):
                    lines.insert(i + 1, "import androidx.compose.foundation.lazy.rememberLazyListState")
                    break

    open(path, "w", encoding="utf-8").write("\n".join(lines))
    return rows, len(insertions) + len(appends)


SCREEN_OF = {}

HEADER = '''package app.marlboroadvance.mpvex.ui.preferences

import app.marlboroadvance.mpvex.R

/**
 * 设置搜索索引（v172 起）—— 由 `tools/gen_pref_index.py` 从各设置页源码抽取，**不要手改**。
 *
 * 为什么需要这一份：手写索引 [SearchablePreferences] 每张页面只登记了「首页那个入口」，
 * 而 `PreferencesScreen.kt` 里内联着上百个配置项（Emby 推荐 / 封面合成 / 随机播放 /
 * 网络浏览 / 数据缓存 / 应用锁 …），标题用的是**中文字面量**而不是 string 资源 ——
 * 两边都对不上，结果就是「很多配置项都搜索不出来」。
 *
 * 抽取口径：源码里的 `title =` 赋值点（`SwitchPreference` / `ListPreference` /
 * `SliderPreference` / `Preference` 与 `ToggleRow` 之类自定义行都算），副标题取紧随
 * 其后的 `summary =` / `subtitle =`，分类取最近一个 `PreferenceSectionHeader`。
 *
 * ⚠️ v174：**只收录「能定位」的行**。定位靠「那一行显示的文字」本身（行上是
 * `Modifier.settingsAnchor(...)`，本文件是 `titleRes` / `title`，同一表达式派生所以
 * 必然一致）。搜得到却点过去没反应，比搜不到更让人困惑，所以定位不了的行干脆不收。
 *
 * ⚠️ 与手写索引**并存**：手写那份带英文关键词（amoled / vulkan / danmaku …），是搜英文的
 *    唯一来源；合并时按 `titleRes` / `title` 去重，手写优先。
 */
internal val generatedSearchablePreferences: List<SearchablePreference> = buildList {
'''


ANCHOR_RX = re.compile(r'settingsAnchor\(\s*(R\.string\.(\w+)|"((?:[^"\\]|\\.)*)")\s*\)')
MULTI_RX = re.compile(r"settingsAnchor\(([^()]*)\)")
STR_RX = re.compile(r'<string\s+name="([\w.]+)"[^>]*>(.*?)</string>', re.S)


def load_strings():
    """资源名 → 各语言下的显示文字（集合）。

    自检必须比「文字」而不是「资源名」：同一句文案常有两套资源名在用
    （`pref_export_settings_title` 与 `i18n_export_settings`），运行时是靠文字对齐的。
    另外有的行标题是**中文字面量**而索引里挂了资源名 —— 中文环境能对上、英文对不上，
    所以按语言逐个比，任一对上就算对得上（贴合实际使用场景）。
    """
    table = {}
    for res in ("values-zh-rCN", "values-zh", "values"):
        f = os.path.join(BASE, "app/src/main/res", res, "strings.xml")
        if not os.path.exists(f):
            continue
        for name, body in STR_RX.findall(open(f, encoding="utf-8").read()):
            txt = re.sub(r"<[^>]+>", "", body)
            txt = (txt.replace("\\'", "'").replace("\\\"", '"')
                      .replace("\\n", "\n").replace("\\@", "@").replace("\\u0022", '"'))
            table.setdefault(name, set()).add(" ".join(txt.split()))
    return table


def verify_curated():
    """自检：手写索引每一条，在它指向的那张页面上到底有没有对应锚点。

    这正是 v173「有的能定位、有的不行」的病根 —— 手写条目根本没有 `anchor` 字段，
    合并时把自动抽取那张（带锚点）吃掉，点进去只到页顶。
    v174 改成「按显示文字对齐」后，这里逐条验一遍：对不上的都是**页面级入口**
    （标题就是那一页的名字），点进去停在页顶是正确行为；如果出现非页面级条目，
    就说明索引指向的行已经不存在了，该改就改。
    """
    strings = load_strings()

    anchor_of = {}   # screen -> set(text)
    for fname, screen, _c, _a, _an, _h in SCREENS:
        path = os.path.join(PREFS, fname)
        if not os.path.exists(path):
            continue
        texts = anchor_of.setdefault(screen, set())
        for m in MULTI_RX.finditer(open(path, encoding="utf-8").read()):
            for arg in m.group(1).split(","):
                arg = arg.strip()
                rm = re.match(r"R\.string\.(\w+)$", arg)
                lm = re.match(r'^"((?:[^"\\]|\\.)*)"$', arg)
                if rm:
                    texts |= strings.get(rm.group(1), {"\0" + rm.group(1)})
                elif lm:
                    texts.add(" ".join(lm.group(1).split()))

    src = open(os.path.join(PREFS, "SearchablePreference.kt"), encoding="utf-8").read()
    bad = []
    total = 0
    for block in src.split("add(SearchablePreference(")[1:]:
        block = block.split("))")[0]
        sm = re.search(r"screen\s*=\s*(\w+)", block)
        if not sm:
            continue
        screen = sm.group(1)
        tm = re.search(r"titleRes\s*=\s*R\.string\.(\w+)", block)
        lm = re.search(r'title\s*=\s*"((?:[^"\\]|\\.)*)"', block)
        if tm:
            name = tm.group(1)
            keys = strings.get(name, {"\0" + name})
        elif lm:
            name = lm.group(1)
            keys = {" ".join(lm.group(1).replace('\\"', '"').split())}
        else:
            continue
        total += 1
        if not (keys & anchor_of.get(screen, set())):
            bad.append((screen, name))
    print(f"curated checked {total} / 该页无对应行 {len(bad)}")
    for screen, t in bad:
        print(f"  NO-ROW {screen} <- {t}")


def main():
    for fname, screen, _c, _a, _an, _h in SCREENS:
        SCREEN_OF[os.path.join(PREFS, fname)] = screen

    all_items = []
    skipped = []
    for fname, screen, cat, aliases, do_anchor, hook in SCREENS:
        path = os.path.join(PREFS, fname)
        if not os.path.exists(path):
            print("MISSING", fname)
            continue
        rows, injected = scan_screen(path, cat, do_anchor, hook)
        kept = [r for r in rows if r["anchored"]]
        lost = [r for r in rows if not r["anchored"]]
        for r in kept:
            r["screen"] = screen
            r["aliases"] = aliases
        for r in lost:
            skipped.append((fname, r))
        print(f"{fname:42s} rows={len(rows):3d} anchored={len(kept):3d} lost={len(lost):2d} new={injected:3d}")
        all_items.extend(kept)

    seen = set()
    uniq = []
    for it in all_items:
        # 一行可能登记多个标题（三元文案）：索引里各出一条，两个文案都搜得到
        for kind, val in it["titles"]:
            key = (kind, val, it["screen"])
            if key in seen:
                continue
            seen.add(key)
            uniq.append(dict(it, titleKind=kind, title=val))

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
    print(f"total {len(all_items)} unique {len(uniq)} / 定位不了被丢弃 {len(skipped)}")
    for fname, r in skipped:
        print(f"  LOST {fname}:{r['line']} {r['titles']}")
    verify_curated()


main()
