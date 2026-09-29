"""把媒体库筛选面板从 AlertDialog + 横向 chip 组，换成 ModalBottomSheet + 下拉展开组。

按行号替换，避免大段字符串匹配出错：
- [dialog_start, dialog_end) 是 `if (showFilterDialog) { ... }`
- [group_start, group_end) 是 `private fun FilterChipGroup(...) { ... }`
"""
import io

p = r"D:\project\mpvEx-master\app\src\main\java\app\marlboroadvance\mpvex\ui\browser\emby\EmbyLibraryScreen.kt"
lines = io.open(p, encoding="utf-8").read().split("\n")


def find(prefix, start=0):
    for i in range(start, len(lines)):
        if lines[i].startswith(prefix):
            return i
    raise SystemExit("not found: " + prefix)


dialog_start = find("    if (showFilterDialog) {")
dialog_end = find("    // 视图样式选择")
group_start = find("private fun FilterChipGroup(")

# 组函数一直持续到文件里下一个顶层 "}"（缩进为 0 的右花括号）
group_end = group_start
while lines[group_end] != "}":
    group_end += 1
group_end += 1

print("dialog:", dialog_start + 1, dialog_end)
print("group:", group_start + 1, group_end)

dialog_new = '''    if (showFilterDialog) {
      var draftGenres by remember { mutableStateOf(selectedGenres) }
      var draftTags by remember { mutableStateOf(selectedTags) }
      var draftYears by remember { mutableStateOf(selectedYears) }
      var draftRatings by remember { mutableStateOf(selectedRatings) }
      var draftMinRating by remember { mutableStateOf(minRating) }
      var draftFavoriteOnly by remember { mutableStateOf(favoriteOnly) }
      var draftPersonIds by remember { mutableStateOf(selectedPersonIds) }
      var draftStudioIds by remember { mutableStateOf(selectedStudioIds) }
      var draftPlayed by remember { mutableStateOf(playedFilter) }
      var draftHd by remember { mutableStateOf(hdFilter) }
      var draft3D by remember { mutableStateOf(threeDFilter) }
      var draftSubtitles by remember { mutableStateOf(subtitlesFilter) }

      ModalBottomSheet(onDismissRequest = { showFilterDialog = false }) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(text = "筛选", style = MaterialTheme.typography.titleMedium)

          // 类型
          FilterDropdown(
            label = "类型",
            options = filterOptions.genres,
            selectedNames = filterOptions.genres.filter { it in draftGenres }.toSet(),
            onToggle = { name ->
              draftGenres = if (name in draftGenres) draftGenres - name else draftGenres + name
            },
            onClear = { draftGenres = emptySet() },
          )
          // 标签
          FilterDropdown(
            label = "标签",
            options = filterOptions.tags,
            selectedNames = filterOptions.tags.filter { it in draftTags }.toSet(),
            onToggle = { name ->
              draftTags = if (name in draftTags) draftTags - name else draftTags + name
            },
            onClear = { draftTags = emptySet() },
          )
          // 年份：下拉里是字符串，切回 Int 再存
          FilterDropdown(
            label = "年份",
            options = filterOptions.years.map { it.toString() },
            selectedNames = draftYears.map { it.toString() }.toSet(),
            onToggle = { v ->
              v.toIntOrNull()?.let { y ->
                draftYears = if (y in draftYears) draftYears - y else draftYears + y
              }
            },
            onClear = { draftYears = emptySet() },
          )
          // 官方分级
          FilterDropdown(
            label = "官方分级",
            options = filterOptions.officialRatings,
            selectedNames = filterOptions.officialRatings.filter { it in draftRatings }.toSet(),
            onToggle = { name ->
              draftRatings = if (name in draftRatings) draftRatings - name else draftRatings + name
            },
            onClear = { draftRatings = emptySet() },
          )
          // 演员 / 导演：chip 上显示名字，实际按 Id 筛（同名不同人只能靠 Id 区分）
          FilterDropdown(
            label = "演员 / 导演",
            options = filterOptions.persons.map { it.name },
            selectedNames = filterOptions.persons
              .filter { it.id in draftPersonIds }
              .map { it.name }
              .toSet(),
            onToggle = { name ->
              val person = filterOptions.persons.firstOrNull { it.name == name } ?: return@FilterDropdown
              draftPersonIds = if (person.id in draftPersonIds) {
                draftPersonIds - person.id
              } else {
                draftPersonIds + person.id
              }
            },
            onClear = { draftPersonIds = emptySet() },
          )
          // 工作室（出品方）
          FilterDropdown(
            label = "工作室",
            options = filterOptions.studios.map { it.name },
            selectedNames = filterOptions.studios
              .filter { it.id in draftStudioIds }
              .map { it.name }
              .toSet(),
            onToggle = { name ->
              val studio = filterOptions.studios.firstOrNull { it.name == name } ?: return@FilterDropdown
              draftStudioIds = if (studio.id in draftStudioIds) {
                draftStudioIds - studio.id
              } else {
                draftStudioIds + studio.id
              }
            },
            onClear = { draftStudioIds = emptySet() },
          )
          // 播放状态：三态（不限 / 已看 / 未看），再点一次已选的就回到「不限」
          FilterDropdown(
            label = "播放状态",
            options = listOf("已看", "未看"),
            selectedNames = when (draftPlayed) {
              true -> setOf("已看")
              false -> setOf("未看")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "已看"
              draftPlayed = if (draftPlayed == target) null else target
            },
            onClear = { draftPlayed = null },
          )
          // 清晰度
          FilterDropdown(
            label = "清晰度",
            options = listOf("高清", "标清"),
            selectedNames = when (draftHd) {
              true -> setOf("高清")
              false -> setOf("标清")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "高清"
              draftHd = if (draftHd == target) null else target
            },
            onClear = { draftHd = null },
          )
          // 3D
          FilterDropdown(
            label = "3D",
            options = listOf("3D", "非 3D"),
            selectedNames = when (draft3D) {
              true -> setOf("3D")
              false -> setOf("非 3D")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "3D"
              draft3D = if (draft3D == target) null else target
            },
            onClear = { draft3D = null },
          )
          // 字幕
          FilterDropdown(
            label = "字幕",
            options = listOf("有字幕", "无字幕"),
            selectedNames = when (draftSubtitles) {
              true -> setOf("有字幕")
              false -> setOf("无字幕")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "有字幕"
              draftSubtitles = if (draftSubtitles == target) null else target
            },
            onClear = { draftSubtitles = null },
          )
          // 评分：单选，再点一次已选档位回到「不限」
          FilterDropdown(
            label = "评分",
            options = RATING_OPTIONS.map { it.first }.filter { it != "不限" },
            selectedNames = RATING_OPTIONS
              .firstOrNull { it.second == draftMinRating }
              ?.let { setOf(it.first) }
              ?: emptySet(),
            onToggle = { v ->
              val target = RATING_OPTIONS.firstOrNull { it.first == v }?.second
              draftMinRating = if (draftMinRating == target) null else target
            },
            onClear = { draftMinRating = null },
          )

          // 只看收藏：打开 = 只在收藏里套用上面的筛选；关掉 = 不限（不是「只看未收藏」）
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "只看收藏",
              style = MaterialTheme.typography.titleSmall,
              modifier = Modifier.weight(1f),
            )
            Switch(
              checked = draftFavoriteOnly,
              onCheckedChange = { draftFavoriteOnly = it },
            )
          }

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            TextButton(
              onClick = {
                selectedGenres = emptySet()
                selectedTags = emptySet()
                selectedYears = emptySet()
                selectedRatings = emptySet()
                minRating = null
                favoriteOnly = false
                selectedPersonIds = emptySet()
                selectedStudioIds = emptySet()
                playedFilter = null
                hdFilter = null
                threeDFilter = null
                subtitlesFilter = null
                persistFilter()
                showFilterDialog = false
              },
              modifier = Modifier.weight(1f),
            ) {
              Text("恢复默认")
            }
            Button(
              onClick = {
                selectedGenres = draftGenres
                selectedTags = draftTags
                selectedYears = draftYears
                selectedRatings = draftRatings
                minRating = draftMinRating
                favoriteOnly = draftFavoriteOnly
                selectedPersonIds = draftPersonIds
                selectedStudioIds = draftStudioIds
                playedFilter = draftPlayed
                hdFilter = draftHd
                threeDFilter = draft3D
                subtitlesFilter = draftSubtitles
                persistFilter()
                showFilterDialog = false
              },
              modifier = Modifier.weight(1f),
            ) {
              Text("确定")
            }
          }
        }
      }
    }

'''

group_new = '''/**
 * 筛选面板里的「下拉选择」：一行摘要 + 点开后纵向铺开全部选项。
 *
 * 原来是横向滑动的 chip 组 —— 类型、演员这种动辄几十项的维度横向滑根本没法找，
 * 而且滑到后面完全不知道还剩多少。改成下拉后一屏能扫十几项，末尾还有「不限」一键清空。
 *
 * 选项为空时整组隐藏：说明这个维度服务端没给或该库没有，不占地方。
 * 展开区最高 260dp，超出后自己在组内滚动，不会把整个面板撑得很长。
 */
@Composable
private fun FilterDropdown(
  label: String,
  options: List<String>,
  selectedNames: Set<String>,
  onToggle: (String) -> Unit,
  onClear: () -> Unit,
) {
  if (options.isEmpty()) return
  var expanded by remember { mutableStateOf(false) }
  // 摘要：没选显示「不限」，选得少就全列出来，选得多只报数量，避免一行塞不下
  val summary = when {
    selectedNames.isEmpty() -> "不限"
    selectedNames.size <= 2 -> selectedNames.joinToString("、")
    else -> "已选 ${selectedNames.size} 项"
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .clickable { expanded = !expanded }
        .padding(horizontal = 14.dp, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "收起" else "展开",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    AnimatedVisibility(visible = expanded) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 260.dp)
          .verticalScroll(rememberScrollState())
          .padding(top = 4.dp),
      ) {
        // 「不限」永远放在第一位，点它等于清空这一组
        FilterDropdownRow(
          text = "不限",
          checked = selectedNames.isEmpty(),
          onClick = { if (selectedNames.isNotEmpty()) onClear() },
        )
        options.forEach { option ->
          FilterDropdownRow(
            text = option,
            checked = option in selectedNames,
            onClick = { onToggle(option) },
          )
        }
      }
    }
  }
}

@Composable
private fun FilterDropdownRow(
  text: String,
  checked: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Checkbox(checked = checked, onCheckedChange = { onClick() })
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
  }
}
'''

# 先替换靠后的组函数，再替换前面的弹窗，避免行号错位
lines[group_start:group_end] = group_new.split("\n")
lines[dialog_start:dialog_end] = dialog_new.split("\n")

io.open(p, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
print("done, lines =", len(lines))
