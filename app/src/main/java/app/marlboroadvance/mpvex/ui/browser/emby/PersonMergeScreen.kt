package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.DuplicateGroup
import app.marlboroadvance.mpvex.domain.emby.DuplicateKind
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.MergeItemRef
import app.marlboroadvance.mpvex.domain.emby.MergePhase
import app.marlboroadvance.mpvex.domain.emby.MERGE_STATE_REVERTED
import app.marlboroadvance.mpvex.domain.emby.PersonMergeBatch
import app.marlboroadvance.mpvex.domain.emby.PersonRef
import app.marlboroadvance.mpvex.domain.emby.autoMergeableGroups
import app.marlboroadvance.mpvex.domain.emby.findOrphanPersons
import app.marlboroadvance.mpvex.domain.emby.toPersonRef
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * 「演职员合并」独立工具页 —— 整个 Person 去重功能的**主场**。
 *
 * 结构（自上而下）：说明卡 → 搜索演员 → 手动绑定（选择区）→ 扫描重复 → 自动合并同名
 * → 孤立演员清理 → 合并历史。
 *
 * ## 为什么所有写操作都先问一句
 * 这是**唯一会改服务器元数据**的页面，而且改坏了**不会报错**（把两个人的作品搅在一起，
 * 界面上看不出来）。所以：需要管理员、自动合并只吃「同名且无 ID 冲突」那一档、
 * 每次落地前都弹确认框并把「保留哪条」摆出来；非管理员整页只读。
 *
 * @param prefillPersonId 从演员卡长按菜单进来时预置的「主条目」；为空则是空页
 */
@Serializable
data class PersonMergeScreen(
  val prefillPersonId: String? = null,
  val prefillPersonName: String? = null,
  val prefillPersonImageTag: String? = null,
) : Screen {

  @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: EmbyViewModel = viewModel(
      factory = EmbyViewModel.factory(context.applicationContext as Application),
    )
    val server by viewModel.currentServer.collectAsState()
    val mergeState by viewModel.mergeState.collectAsState()
    val scope = rememberCoroutineScope()
    val browserPreferences = koinInject<BrowserPreferences>()

    // ── 管理员探测：写元数据需要管理员，非管理员整页只读 ──
    // ⚠️ 单独一次请求；拿不到（网络/权限异常）一律按「不是管理员」处理 ——
    // 宁可少给一个能用的按钮，也不能让用户点了之后静默 403。
    var isAdmin by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(server?.id) {
      val s = server
      isAdmin = if (s == null) null else viewModel.isEmbyAdmin(s)
    }
    val writable = isAdmin == true

    // ── 搜索 ──
    var term by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<PersonRef>>(emptyList()) }
    var searchMessage by remember { mutableStateOf<String?>(null) }

    // ── 选择（勾选参与的条目 + 单选保留项）──
    var picked by remember { mutableStateOf<Map<String, PersonRef>>(emptyMap()) }
    var canonicalId by remember { mutableStateOf<String?>(null) }

    // ── 扫描重复 ──
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanText by remember { mutableStateOf("") }

    // ── 合并确认（选择区的「合并」按钮 / 每个组的「合并…」都走这里）──
    var mergeConfirmOpen by remember { mutableStateOf(false) }
    var previewItems by remember { mutableStateOf<List<MergeItemRef>?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }

    // ── 自动合并确认 ──
    var autoGroupsToConfirm by remember { mutableStateOf<List<DuplicateGroup>?>(null) }

    // ── 孤立演员 ──
    var autoClean by remember { mutableStateOf(browserPreferences.embyAutoCleanOrphans.get()) }
    var orphanScanning by remember { mutableStateOf(false) }
    var orphanScanText by remember { mutableStateOf("") }
    var orphanMessage by remember { mutableStateOf<String?>(null) }
    var cleanCandidates by remember { mutableStateOf<List<String>?>(null) }
    var fullScanConfirm by remember { mutableStateOf(false) }

    // ── 合并历史 ──
    // ⚠️ 读账本是**同步文件 IO**（一个批次一个 JSON，最多 20 个）—— 必须挪到 IO 线程，
    // 直接在组合/主线程里读会卡帧，而且还发生在每次任务收尾时。
    var history by remember { mutableStateOf<List<PersonMergeBatch>>(emptyList()) }
    LaunchedEffect(Unit) {
      history = withContext(Dispatchers.IO) { viewModel.mergeHistory() }
    }
    // 每次任务跑完（running 落回 false）重读一次账本，新批次立刻出现在历史里
    LaunchedEffect(mergeState.running) {
      if (!mergeState.running) {
        history = withContext(Dispatchers.IO) { viewModel.mergeHistory() }
      }
    }

    // ── 预置主条目（从演员卡长按进来）──
    LaunchedEffect(prefillPersonId, server?.id) {
      val s = server ?: return@LaunchedEffect
      val pid = prefillPersonId ?: return@LaunchedEffect
      if (canonicalId != null) return@LaunchedEffect
      // 名字 / 头像 tag 由导航参数带过来，作品数另查；查不到就按 0 处理（不影响合并正确性）
      val count = runCatching { viewModel.loadPersonWorkCounts(s, listOf(pid))[pid] ?: 0 }.getOrDefault(0)
      val ref = PersonRef(
        id = pid,
        name = prefillPersonName.orEmpty().ifBlank { pid },
        imageTag = prefillPersonImageTag,
        workCount = count,
      )
      picked = picked + (ref.id to ref)
      canonicalId = ref.id
    }

    /** 勾选 / 取消勾选一个条目；取消掉的恰好是保留项时，把保留项顺延到剩下的第一个 */
    fun togglePick(p: PersonRef) {
      val cur = picked
      picked = if (cur.containsKey(p.id)) {
        val next = cur - p.id
        if (canonicalId == p.id) canonicalId = next.keys.firstOrNull()
        next
      } else {
        cur + (p.id to p)
      }
    }

    fun setCanonical(p: PersonRef) {
      canonicalId = p.id
      if (!picked.containsKey(p.id)) picked = picked + (p.id to p)
    }

    fun doSearch() {
      val s = server ?: return
      val t = term.trim()
      if (t.isEmpty()) {
        searchMessage = "先输入要搜的名字"
        return
      }
      scope.launch {
        searching = true
        searchMessage = null
        searchResults = emptyList()
        val found = viewModel.searchPersonsForMerge(s, t).mapNotNull { it.toPersonRef() }
        // 作品数另查（/Persons 不支持 ChildCount，见 searchPersons 的注释）
        val counts = runCatching {
          viewModel.loadPersonWorkCounts(s, found.map { it.id })
        }.getOrDefault(emptyMap())
        searchResults = found.map { it.copy(workCount = counts[it.id] ?: 0) }
        if (found.isEmpty()) searchMessage = "没有搜到「$t」"
        searching = false
      }
    }

    fun doScanDuplicates() {
      val s = server ?: return
      scope.launch {
        scanning = true
        scanText = "正在拉取人员列表…"
        groups = null
        val result = runCatching {
          viewModel.scanDuplicateGroups(s) { done, total -> scanText = "正在统计作品数 $done/$total" }
        }
        result
          .onSuccess { groups = it }
          .onFailure {
            if (it is CancellationException) throw it
            scanText = "扫描失败：${it.message ?: "未知错误"}"
          }
        scanning = false
      }
    }

    /** 打开「合并确认」并顺手做一次精确预览（会影响到几部作品） */
    fun openMergeConfirm() {
      val canon = picked[canonicalId] ?: return
      val members = picked.values.filter { it.id != canon.id }
      if (members.isEmpty()) return
      mergeConfirmOpen = true
      previewItems = null
      previewError = null
    }

    LaunchedEffect(mergeConfirmOpen) {
      if (!mergeConfirmOpen) return@LaunchedEffect
      val s = server ?: return@LaunchedEffect
      val canon = picked[canonicalId] ?: return@LaunchedEffect
      val members = picked.values.filter { it.id != canon.id }
      if (members.isEmpty()) return@LaunchedEffect
      previewLoading = true
      previewError = null
      runCatching { viewModel.previewMergeItems(s, members) }
        .onSuccess { previewItems = it }
        .onFailure {
          if (it is CancellationException) throw it
          previewError = it.message ?: "预览失败"
        }
      previewLoading = false
    }

    /**
     * 扫孤立演员。
     *
     * @param all true = 全库范围（含非本次合并产生的），false = 只在账本出现过的 Id 里找。
     *   全库那档**逐个**问作品数（一人一次请求），所以有数量上限，超了直接劝退。
     */
    fun scanOrphans(all: Boolean) {
      val s = server ?: return
      scope.launch {
        orphanScanning = true
        orphanMessage = null
        try {
          val ids = if (all) {
            orphanScanText = "正在拉取全部人员…"
            viewModel.allPersonIds(s)
          } else {
            // 账本读取同样是同步文件 IO，挪到 IO 线程
            withContext(Dispatchers.IO) { viewModel.knownMergeMemberIds().toList() }
          }
          if (ids.isEmpty()) {
            orphanMessage = if (all) {
              "服务器上没有人员记录"
            } else {
              "合并历史里还没有记录，没有可检查的范围（可改用「扫描全部演员」）"
            }
            return@launch
          }
          if (all && ids.size > ORPHAN_FULL_SCAN_CAP) {
            orphanMessage = "服务器上有 ${ids.size} 位人员，超出单次核对上限（$ORPHAN_FULL_SCAN_CAP）。" +
              "请改用「清理孤立演员」只检查合并过的那些。"
            return@launch
          }
          val counts = viewModel.loadPersonWorkCounts(s, ids) { done, total ->
            orphanScanText = "正在核对作品数 $done/$total"
          }
          val orphans = findOrphanPersons(counts, ids)
          if (orphans.isEmpty()) {
            orphanMessage = "在 ${ids.size} 位人员里没有发现「已无任何作品」的演员"
          } else {
            cleanCandidates = orphans
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          orphanMessage = "扫描失败：${e.message ?: "未知错误"}"
        } finally {
          orphanScanning = false
        }
      }
    }

    Column(modifier = Modifier.fillMaxSize()) {
      TopAppBar(
        title = { Text("演职员合并") },
        navigationIcon = {
          IconButton(onClick = { backStack.removeLastOrNull() }) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "返回",
            )
          }
        },
        actions = { AdminBadge(isAdmin) },
      )

      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部留 96dp：外层 MainScreen 的底部导航栏是**浮在内容之上**的
        // （它的 innerPadding 被刻意忽略），不留这截的话最后那张「合并历史」卡
        // 会连着「撤销」按钮一起被导航栏盖住。
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item { InfoCard(isAdmin) }

        val s = server
        if (s == null) {
          item {
            SectionCard(title = "尚未连接服务器") {
              Text(
                text = "请先到「设置 → Emby」里添加并选中一个服务器，再回来使用演职员合并。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          // ② 搜索演员
          item {
            SectionCard(title = "搜索演员") {
              OutlinedTextField(
                value = term,
                onValueChange = { term = it },
                label = { Text("演员名字") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                trailingIcon = {
                  IconButton(enabled = !searching, onClick = { doSearch() }) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                  }
                },
              )
              Spacer(Modifier.height(8.dp))
              Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { doSearch() }, enabled = !searching) {
                  Text(if (searching) "搜索中…" else "搜索")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                  text = "长按任意行可复制名字；点「并入 / 设为主条目」把它加进下面的选择区。",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.weight(1f),
                )
              }
              searchMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                  text = it,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }
              if (searchResults.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                searchResults.forEach { p ->
                  PersonPickRow(
                    person = p,
                    server = s,
                    picked = picked,
                    canonicalId = canonicalId,
                    enabled = writable,
                    onToggle = { togglePick(p) },
                    onSetCanonical = { setCanonical(p) },
                    // 这里搜的是服务器上的「人员」（含导演 / 编剧），不写死成「演员」
                    onCopyId = { copyToClipboard(context, "人员 Id", p.id) },
                  )
                }
              }
            }
          }

          // ③ 手动绑定（选择区）
          item {
            SectionCard(title = "手动绑定（可跨不同名字）") {
              val canon = picked[canonicalId]
              val members = picked.values.filter { it.id != canon?.id }
              if (canon == null) {
                Text(
                  text = "还没有选主条目：在上面的搜索结果里点「设为主条目」，或直接点某个组的「合并…」。",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else {
                Text(
                  text = "主条目（保留它）",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                PersonSummaryRow(person = canon, server = s, highlight = true)
                Spacer(Modifier.height(10.dp))
                Text(
                  text = "要并入的条目（${members.size}）",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                if (members.isEmpty()) {
                  Text(
                    text = "还没有勾选任何要并入的条目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                } else {
                  members.forEach { m ->
                    PersonSummaryRow(
                      person = m,
                      server = s,
                      onRemove = { togglePick(m) },
                      removeEnabled = writable,
                    )
                  }
                }
                Spacer(Modifier.height(10.dp))
                // ⚠️ 用 FlowRow 而不是 Row：Row 装不下时会把子项**压窄**，
                // 中文按钮一压就折行 / 被按钮高度裁掉，看上去就是「文字显示不全被截断」。
                // FlowRow 装不下就整体换到下一行，按钮永远保持完整宽度。
                FlowRow(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Button(
                    onClick = { openMergeConfirm() },
                    enabled = writable && members.isNotEmpty(),
                  ) {
                    Text("合并 ${picked.size} 条", maxLines = 1)
                  }
                  OutlinedButton(
                    onClick = {
                      picked = emptyMap()
                      canonicalId = null
                      previewItems = null
                    },
                  ) {
                    Text("清空选择", maxLines = 1)
                  }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                  text = "合并会写入服务器元数据（所有客户端生效），之后可撤销。",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          // ④ 扫描重复
          item {
            SectionCard(title = "扫描重复演员") {
              Text(
                text = "拉取服务器全部人员，按名字找疑似重复的。同名档才算「确定」；名字相近档只作建议，需人工确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(8.dp))
              Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { doScanDuplicates() }, enabled = !scanning) {
                  Text(if (scanning) "扫描中…" else "扫描重复演员", maxLines = 1)
                }
                if (scanning && scanText.isNotBlank()) {
                  Spacer(Modifier.width(12.dp))
                  Text(
                    text = scanText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              val gs = groups
              if (gs != null) {
                Spacer(Modifier.height(8.dp))
                if (gs.isEmpty()) {
                  Text(
                    text = "没有发现重复的演员。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                } else {
                  val sameName = gs.filter { it.kind == DuplicateKind.SAME_NAME }
                  val similar = gs.filter { it.kind == DuplicateKind.SIMILAR_NAME }
                  if (sameName.isNotEmpty()) {
                    GroupHeader("同名（确定）· ${sameName.size} 组")
                    sameName.forEach { g ->
                      DuplicateGroupRow(
                        group = g,
                        server = s,
                        enabled = writable,
                        onMerge = {
                          picked = g.members.associateBy { it.id }
                          canonicalId = g.suggestedCanonical.id
                          openMergeConfirm()
                        },
                      )
                    }
                  }
                  if (similar.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    GroupHeader("名字相近（建议，需确认）· ${similar.size} 组")
                    similar.forEach { g ->
                      DuplicateGroupRow(
                        group = g,
                        server = s,
                        enabled = writable,
                        onMerge = {
                          picked = g.members.associateBy { it.id }
                          canonicalId = g.suggestedCanonical.id
                          openMergeConfirm()
                        },
                      )
                    }
                  }
                }
              }
            }
          }

          // ⑤ 自动合并全部同名
          item {
            val autoGroups = groups?.let { autoMergeableGroups(it) } ?: emptyList()
            SectionCard(title = "自动合并全部同名") {
              Text(
                text = "只处理「规范化后名字完全相同」的组，且跳过「同名但外部 ID 冲突」（那多半是两个同名的不同人）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(8.dp))
              if (groups == null) {
                Text(
                  text = "先点上面的「扫描重复演员」，这里才知道有多少组可合并。",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else {
                Button(
                  onClick = { autoGroupsToConfirm = autoGroups },
                  enabled = writable && autoGroups.isNotEmpty(),
                ) {
                  Text("自动合并全部同名（${autoGroups.size} 组）", maxLines = 1)
                }
              }
            }
          }

          // ⑥ 孤立演员清理
          item {
            SectionCard(title = "孤立演员清理") {
              Text(
                text = "合并后，被并掉的演员不再挂在任何作品上，但服务器可能还留着条目与头像。" +
                  "清理会触发服务端自己的「刷新人员」任务把它们回收 —— 不会删除任何影片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(8.dp))
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text("合并完成后自动清理", style = MaterialTheme.typography.bodyMedium)
                  Text(
                    text = "会替你触发一次服务端任务；任务跑了什么你看不见，所以默认关。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = autoClean,
                  onCheckedChange = {
                    autoClean = it
                    browserPreferences.embyAutoCleanOrphans.set(it)
                  },
                )
              }
              Spacer(Modifier.height(8.dp))
              FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                OutlinedButton(
                  onClick = { scanOrphans(all = false) },
                  enabled = !orphanScanning,
                ) {
                  Text("清理孤立演员", maxLines = 1)
                }
                OutlinedButton(
                  onClick = { fullScanConfirm = true },
                  enabled = !orphanScanning,
                ) {
                  Text("扫描全部演员", maxLines = 1)
                }
              }
              if (orphanScanning && orphanScanText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                  text = orphanScanText,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              orphanMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                  text = it,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          // ⑦ 合并历史
          item {
            SectionCard(title = "合并历史（最近 ${history.size} 批）") {
              if (history.isEmpty()) {
                Text(
                  text = "还没有合并记录。",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else {
                history.forEach { batch ->
                  HistoryRow(
                    batch = batch,
                    enabled = writable && batch.canUndo,
                    onUndo = { viewModel.startUndo(s, batch) },
                  )
                }
              }
            }
          }
        }
      }
    }

    // ── 任务进行中的进度框 ──
    if (mergeState.running) {
      AlertDialog(
        onDismissRequest = { /* 合并中不允许点外部关掉，避免误以为已经停了 */ },
        title = { Text(mergeState.title.ifBlank { "正在合并" }) },
        text = {
          Column {
            Text(
              text = when (mergeState.phase) {
                MergePhase.COLLECTING -> "正在收集相关作品…"
                MergePhase.WRITING -> "正在写入服务器…"
                MergePhase.DONE -> "正在收尾…"
              },
              style = MaterialTheme.typography.bodyMedium,
            )
            if (mergeState.total > 0) {
              Spacer(Modifier.height(10.dp))
              ProgressBar(mergeState.done.toFloat() / mergeState.total.toFloat())
              Spacer(Modifier.height(6.dp))
              Text(
                text = "${mergeState.done} / ${mergeState.total}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (mergeState.currentName.isNotBlank()) {
              Spacer(Modifier.height(6.dp))
              Text(
                text = "当前：${mergeState.currentName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Spacer(Modifier.height(6.dp))
            Text(
              text = "已改写 ${mergeState.wrote} 部" +
                if (mergeState.failed > 0) "，失败 ${mergeState.failed} 部" else "",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        confirmButton = {
          TextButton(onClick = { viewModel.cancelMerge() }) { Text("取消") }
        },
      )
    }

    // ── 结果提示 ──
    val message = mergeState.message
    if (message != null && !mergeState.running) {
      AlertDialog(
        onDismissRequest = { viewModel.clearMergeMessage() },
        title = { Text("提示") },
        text = { Text(message) },
        confirmButton = {
          TextButton(onClick = { viewModel.clearMergeMessage() }) { Text("知道了") }
        },
      )
    }

    // ── 合并确认（保留谁 / 并掉谁 + 会改写多少部）──
    if (mergeConfirmOpen) {
      val canon = picked[canonicalId]
      val members = picked.values.filter { it.id != canon?.id }
      if (canon == null || members.isEmpty()) {
        // 选择在弹窗打开后被清空（理论上不会走到，兜底直接关掉）
        LaunchedEffect(Unit) { mergeConfirmOpen = false }
      } else {
        AlertDialog(
          onDismissRequest = { mergeConfirmOpen = false },
          title = { Text("确认合并") },
          text = {
            Column {
              Text(
                text = "保留：${canon.name}（${canon.workCount} 部）",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Spacer(Modifier.height(6.dp))
              Text(
                text = "并入：" + members.joinToString("、") { m ->
                  if (m.name == canon.name) "${m.name}(${shortId(m.id)})" else m.name
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(10.dp))
              when {
                previewLoading -> Text(
                  text = "正在统计会影响到的作品…",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                previewError != null -> Text(
                  text = "预览失败（不影响合并）：$previewError",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.error,
                )
                previewItems != null -> Text(
                  text = "将重写 ${previewItems!!.size} 部作品的演员引用。",
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
              Spacer(Modifier.height(8.dp))
              // 与保留项同名的成员：合并引擎会先给它们改临时名（绕过服务端按名字归并的
              // 行为，否则同名合并永远落不了库），撤销时恢复原名 —— 这里提前说清楚
              val sameNameCount = members.count {
                it.name.trim().equals(canon.name.trim(), ignoreCase = true)
              }
              if (sameNameCount > 0) {
                Text(
                  text = "其中 $sameNameCount 条与保留项同名：会先把它们临时改名" +
                    "（如「${canon.name} #尾号」）再合并 —— 服务器按名字归并演员，" +
                    "同名会互相顶掉；撤销时自动恢复原名。",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
              }
              Text(
                text = "写入服务器后所有客户端立即生效，之后可在「合并历史」里撤销。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          confirmButton = {
            TextButton(onClick = {
              val s = server
              mergeConfirmOpen = false
              if (s != null) viewModel.startMerge(s, canon, members)
            }) {
              Text("合并")
            }
          },
          dismissButton = {
            TextButton(onClick = { mergeConfirmOpen = false }) { Text("取消") }
          },
        )
      }
    }

    // ── 自动合并确认 ──
    autoGroupsToConfirm?.let { gs ->
      AlertDialog(
        onDismissRequest = { autoGroupsToConfirm = null },
        title = { Text("自动合并全部同名") },
        text = {
          Column {
            Text(
              text = "将处理 ${gs.size} 组，保留项按「作品最多 → 名字最长 → 字典序」自动选：",
              style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            gs.take(20).forEach { g ->
              val c = g.suggestedCanonical
              Text(
                text = "· ${g.members.joinToString("、") { it.name }} → 保留「${c.name}」（${c.workCount} 部）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (gs.size > 20) {
              Text(
                text = "…以及另外 ${gs.size - 20} 组",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Spacer(Modifier.height(8.dp))
            Text(
              text = "整个批次完成后可在「合并历史」里一键撤销。",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        confirmButton = {
          TextButton(onClick = {
            val s = server
            autoGroupsToConfirm = null
            if (s != null) viewModel.startAutoMerge(s, gs)
          }) {
            Text("开始合并")
          }
        },
        dismissButton = {
          TextButton(onClick = { autoGroupsToConfirm = null }) { Text("取消") }
        },
      )
    }

    // ── 孤立演员清理确认 ──
    cleanCandidates?.let { ids ->
      AlertDialog(
        onDismissRequest = { cleanCandidates = null },
        title = { Text("清理孤立演员") },
        text = {
          Text(
            text = "有 ${ids.size} 位演员已经不再挂在任何作品上。\n\n" +
              "确认后会触发服务端自己的「刷新人员」任务回收它们 —— " +
              "不会删除任何影片，也不影响任何演员的作品。任务在后台执行，稍后才生效。",
          )
        },
        confirmButton = {
          TextButton(onClick = {
            val s = server
            cleanCandidates = null
            if (s != null) viewModel.startCleanOrphans(s, ids, auto = false)
          }) {
            Text("确认清理")
          }
        },
        dismissButton = {
          TextButton(onClick = { cleanCandidates = null }) { Text("取消") }
        },
      )
    }

    // ── 全库扫描前的确认（很重，必须说清楚）──
    if (fullScanConfirm) {
      AlertDialog(
        onDismissRequest = { fullScanConfirm = false },
        title = { Text("扫描全部演员") },
        text = {
          Text(
            text = "这一步会把服务器上**每一位**演员都问一遍作品数，" +
              "人员多的时候会比较慢（每人一次请求）。\n\n" +
              "它会把「本来就没作品、但你还想留着」的演员也列出来。如果不确定，建议先用左边的「清理孤立演员」。",
          )
        },
        confirmButton = {
          TextButton(onClick = {
            fullScanConfirm = false
            scanOrphans(all = true)
          }) {
            Text("继续扫描")
          }
        },
        dismissButton = {
          TextButton(onClick = { fullScanConfirm = false }) { Text("取消") }
        },
      )
    }
  }
}

/** 全库孤立核对的人员上限：一人一次请求，再多就是拿用户的电量和流量开玩笑 */
private const val ORPHAN_FULL_SCAN_CAP = 800

// ══════════════════════════════════════════════════════════════════════════
// 子组件
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun AdminBadge(isAdmin: Boolean?) {
  val (label, tint) = when (isAdmin) {
    true -> "管理员" to MaterialTheme.colorScheme.primary
    false -> "只读" to MaterialTheme.colorScheme.error
    null -> "检测中" to MaterialTheme.colorScheme.onSurfaceVariant
  }
  Row(
    modifier = Modifier.padding(end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (isAdmin == true) {
      Icon(
        Icons.Default.Check,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(16.dp),
      )
      Spacer(Modifier.width(4.dp))
    }
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint)
  }
}

@Composable
private fun InfoCard(isAdmin: Boolean?) {
  SectionCard(title = "关于演职员合并") {
    Text(
      text = "同一个演员因为名字写法不同（中英文 / 括号注释 / 全角半角 / 艺名），" +
        "在服务器上会裂成多条 Person：作品页缺片、演职员表出现重复面孔、收藏对不上。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
      text = "这里做的事：把多条 Person 的作品引用统一到一条上，写入服务器元数据，" +
        "所有客户端立即生效，之后可以撤销。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
      text = "改不了名字、删不了 Person、换不了头像 —— 保留项叫什么、用什么头像，由你选哪条决定。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (isAdmin == false) {
      Spacer(Modifier.height(8.dp))
      Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "当前 Emby 账号不是管理员：可以浏览与查找，但所有写入操作已禁用。请换管理员账号登录后再来。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
          modifier = Modifier.padding(10.dp),
        )
      }
    }
  }
}

@Composable
private fun SectionCard(
  title: String,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(10.dp))
      content()
    }
  }
}

@Composable
private fun GroupHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
  )
}

@Composable
private fun DuplicateGroupRow(
  group: DuplicateGroup,
  server: EmbyServer?,
  enabled: Boolean,
  onMerge: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        group.members.take(3).forEach { m ->
          PersonAvatar(server, m, 28)
          Spacer(Modifier.width(4.dp))
        }
        Text(
          text = "${group.members.size} 条",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.height(2.dp))
      Text(
        text = group.members.joinToString("、") { it.name },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "建议保留：${group.suggestedCanonical.name} · 合计 ${group.totalWorks} 部作品" +
          if (group.conflictingIds) " · ⚠ 外部 ID 冲突（可能是两个同名的人）" else "",
        style = MaterialTheme.typography.labelSmall,
        color = if (group.conflictingIds) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    TextButton(onClick = onMerge, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
      Text("合并", maxLines = 1)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonPickRow(
  person: PersonRef,
  server: EmbyServer?,
  picked: Map<String, PersonRef>,
  canonicalId: String?,
  enabled: Boolean,
  onToggle: () -> Unit,
  onSetCanonical: () -> Unit,
  onCopyId: () -> Unit,
) {
  val checked = picked.containsKey(person.id)
  val isCanonical = canonicalId == person.id
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
      PersonAvatar(server, person, 36)
      Spacer(Modifier.width(10.dp))
      // 名字支持长按复制（按设计方案：工具页结果行长按 = 复制名字）。
      // 只挂在名字这一列上，不挂整行 —— 整行还要响应复选框 / 单选按钮的点击。
      Column(
        modifier = Modifier
          .weight(1f)
          .longPressToCopy(person.name, "姓名"),
      ) {
        Text(
          text = person.name,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (isCanonical) FontWeight.SemiBold else FontWeight.Normal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${person.workCount} 部作品 · Id ${shortId(person.id)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (checked) {
        RadioButton(selected = isCanonical, onClick = { if (!isCanonical) onSetCanonical() }, enabled = enabled)
      }
    }
    // ⚠️ 操作按钮**必须是单独一行**。原先把它们塞在上面的 Row 里（复选框 + 头像 +
    // 名字 + 两个中文按钮），窄屏或系统放大字号时 Row 会压缩子项，中文按钮被压到折行
    // 再被按钮高度裁掉 —— 就是「文字显示不全、被截断」。单开一行 + FlowRow（装不下
    // 整体换行）+ maxLines=1，三件事合起来保证按钮文字在任何屏幕上都完整。
    FlowRow(
      modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (isCanonical) {
        Text(
          text = "★ 主条目（保留它）",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
      } else {
        TextButton(
          onClick = onSetCanonical,
          enabled = enabled,
          contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
          Text("设为主条目", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
      }
      TextButton(onClick = onCopyId, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("复制 ID", style = MaterialTheme.typography.labelSmall, maxLines = 1)
      }
    }
  }
}

@Composable
private fun PersonSummaryRow(
  person: PersonRef,
  server: EmbyServer?,
  highlight: Boolean = false,
  onRemove: (() -> Unit)? = null,
  removeEnabled: Boolean = true,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PersonAvatar(server, person, 32)
    Spacer(Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = person.name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "${person.workCount} 部作品 · Id ${shortId(person.id)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (onRemove != null) {
      TextButton(onClick = onRemove, enabled = removeEnabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("移除", style = MaterialTheme.typography.labelSmall, maxLines = 1)
      }
    }
  }
}

@Composable
private fun HistoryRow(
  batch: PersonMergeBatch,
  enabled: Boolean,
  onUndo: () -> Unit,
) {
  val whenText = remember(batch.createdAt) {
    runCatching {
      SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(batch.createdAt))
    }.getOrDefault("")
  }
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "$whenText · ${batch.mergedFrom} 条 → 1 条（保留「${batch.canonicalName}」）",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = buildString {
          append("改写 ${batch.touchedCount} 部作品")
          if (batch.failures.isNotEmpty()) append("，失败 ${batch.failures.size} 部")
          if (!batch.verified) append("，⚠ 未确认是否入库")
          if (batch.state == MERGE_STATE_REVERTED) append(" · 已撤销")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    TextButton(onClick = onUndo, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
      Text(if (batch.state == MERGE_STATE_REVERTED) "已撤销" else "撤销")
    }
  }
}

@Composable
private fun PersonAvatar(server: EmbyServer?, person: PersonRef, sizeDp: Int) {
  val px = sizeDp * 3
  val url = remember(server?.id, person.id, person.imageTag) {
    server?.let { EmbyClient.imageUrl(it, person.id, "Primary", person.imageTag, maxWidth = px) }
  }
  Box(
    modifier = Modifier
      .size(sizeDp.dp)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    if (url != null) {
      EmbyImage(
        url = url,
        contentDescription = person.name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        placeholder = Icons.Default.Person,
        maxWidth = px,
      )
    } else {
      Icon(
        Icons.Default.Person,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size((sizeDp / 2).dp),
      )
    }
  }
}

/** 手搓进度条：几个 material3 alpha 版本里 `LinearProgressIndicator` 的签名不一样，不绑它 */
@Composable
private fun ProgressBar(fraction: Float) {
  val f = fraction.coerceIn(0f, 1f)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(6.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(f)
        .fillMaxHeight()
        .background(MaterialTheme.colorScheme.primary),
    )
  }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
  if (text.isBlank()) return
  runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
  }
}

private fun shortId(id: String): String = if (id.length > 8) id.take(8) + "…" else id
