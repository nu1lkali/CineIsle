"""筛选面板改为「即选即生效」：去掉底部确定按钮，直接改真实状态并落盘。"""
import io

p = r"D:\project\mpvEx-master\app\src\main\java\app\marlboroadvance\mpvex\ui\browser\emby\EmbyLibraryScreen.kt"
lines = io.open(p, encoding="utf-8").read().split("\n")


def find(prefix, start=0):
    for i in range(start, len(lines)):
        if lines[i].startswith(prefix):
            return i
    raise SystemExit("not found: " + prefix)


start = find("    if (showFilterDialog) {")
end = find("    // 视图样式选择")
print("block:", start + 1, end)

new = '''    // ── 筛选面板：底部上划，每个维度一个下拉；选中立即生效，不用点确定 ──
    // 之所以不用「草稿 + 确定」：筛选的结果在下面列表里是实时可见的，
    // 每点一项就刷新一次，比「点完确定才知道对不对」少一次试错。
    if (showFilterDialog) {
      ModalBottomSheet(onDismissRequest = { showFilterDialog = false }) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "筛选",
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.weight(1f),
            )
            // 恢复默认：清空全部条件并落盘，面板保持打开，列表立刻回到全量
            TextButton(onClick = {
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
            }) {
              Text("恢复默认")
            }
          }

          // 类型：多选
          FilterDropdown(
            label = "类型",
            options = filterOptions.genres,
            selectedNames = filterOptions.genres.filter { it in selectedGenres }.toSet(),
            onToggle = { name ->
              selectedGenres = if (name in selectedGenres) selectedGenres - name else selectedGenres + name
              persistFilter()
            },
            onClear = { selectedGenres = emptySet(); persistFilter() },
          )
          // 标签：多选
          FilterDropdown(
            label = "标签",
            options = filterOptions.tags,
            selectedNames = filterOptions.tags.filter { it in selectedTags }.toSet(),
            onToggle = { name ->
              selectedTags = if (name in selectedTags) selectedTags - name else selectedTags + name
              persistFilter()
            },
            onClear = { selectedTags = emptySet(); persistFilter() },
          )
          // 年份：下拉里是字符串，切回 Int 再存（多选）
          FilterDropdown(
            label = "年份",
            options = filterOptions.years.map { it.toString() },
            selectedNames = selectedYears.map { it.toString() }.toSet(),
            onToggle = { v ->
              v.toIntOrNull()?.let { y ->
                selectedYears = if (y in selectedYears) selectedYears - y else selectedYears + y
                persistFilter()
              }
            },
            onClear = { selectedYears = emptySet(); persistFilter() },
          )
          // 官方分级：多选
          FilterDropdown(
            label = "官方分级",
            options = filterOptions.officialRatings,
            selectedNames = filterOptions.officialRatings.filter { it in selectedRatings }.toSet(),
            onToggle = { name ->
              selectedRatings = if (name in selectedRatings) selectedRatings - name else selectedRatings + name
              persistFilter()
            },
            onClear = { selectedRatings = emptySet(); persistFilter() },
          )
          // 演员 / 导演：显示名字，按 Id 筛（同名不同人只能靠 Id 区分），多选
          FilterDropdown(
            label = "演员 / 导演",
            options = filterOptions.persons.map { it.name },
            selectedNames = filterOptions.persons
              .filter { it.id in selectedPersonIds }
              .map { it.name }
              .toSet(),
            onToggle = { name ->
              val person = filterOptions.persons.firstOrNull { it.name == name } ?: return@FilterDropdown
              selectedPersonIds = if (person.id in selectedPersonIds) {
                selectedPersonIds - person.id
              } else {
                selectedPersonIds + person.id
              }
              persistFilter()
            },
            onClear = { selectedPersonIds = emptySet(); persistFilter() },
          )
          // 工作室：多选
          FilterDropdown(
            label = "工作室",
            options = filterOptions.studios.map { it.name },
            selectedNames = filterOptions.studios
              .filter { it.id in selectedStudioIds }
              .map { it.name }
              .toSet(),
            onToggle = { name ->
              val studio = filterOptions.studios.firstOrNull { it.name == name } ?: return@FilterDropdown
              selectedStudioIds = if (studio.id in selectedStudioIds) {
                selectedStudioIds - studio.id
              } else {
                selectedStudioIds + studio.id
              }
              persistFilter()
            },
            onClear = { selectedStudioIds = emptySet(); persistFilter() },
          )
          // 播放状态：单选，再点一次已选的那项就取消
          FilterDropdown(
            label = "播放状态",
            options = listOf("已看", "未看"),
            selectedNames = when (playedFilter) {
              true -> setOf("已看")
              false -> setOf("未看")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "已看"
              playedFilter = if (playedFilter == target) null else target
              persistFilter()
            },
            onClear = { playedFilter = null; persistFilter() },
          )
          // 清晰度：单选
          FilterDropdown(
            label = "清晰度",
            options = listOf("高清", "标清"),
            selectedNames = when (hdFilter) {
              true -> setOf("高清")
              false -> setOf("标清")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "高清"
              hdFilter = if (hdFilter == target) null else target
              persistFilter()
            },
            onClear = { hdFilter = null; persistFilter() },
          )
          // 3D：单选
          FilterDropdown(
            label = "3D",
            options = listOf("3D", "非 3D"),
            selectedNames = when (threeDFilter) {
              true -> setOf("3D")
              false -> setOf("非 3D")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "3D"
              threeDFilter = if (threeDFilter == target) null else target
              persistFilter()
            },
            onClear = { threeDFilter = null; persistFilter() },
          )
          // 字幕：单选
          FilterDropdown(
            label = "字幕",
            options = listOf("有字幕", "无字幕"),
            selectedNames = when (subtitlesFilter) {
              true -> setOf("有字幕")
              false -> setOf("无字幕")
              null -> emptySet()
            },
            onToggle = { v ->
              val target = v == "有字幕"
              subtitlesFilter = if (subtitlesFilter == target) null else target
              persistFilter()
            },
            onClear = { subtitlesFilter = null; persistFilter() },
          )
          // 评分：单选
          FilterDropdown(
            label = "评分",
            options = RATING_OPTIONS.map { it.first }.filter { it != "不限" },
            selectedNames = RATING_OPTIONS
              .firstOrNull { it.second == minRating }
              ?.let { setOf(it.first) }
              ?: emptySet(),
            onToggle = { v ->
              val target = RATING_OPTIONS.firstOrNull { it.first == v }?.second
              minRating = if (minRating == target) null else target
              persistFilter()
            },
            onClear = { minRating = null; persistFilter() },
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
              checked = favoriteOnly,
              onCheckedChange = {
                favoriteOnly = it
                persistFilter()
              },
            )
          }
        }
      }
    }

'''

lines[start:end] = new.split("\n")
io.open(p, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
print("done, lines =", len(lines))
