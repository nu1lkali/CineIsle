package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyUserData
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyMediaCard
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySkeletonGrid
import app.marlboroadvance.mpvex.ui.browser.emby.components.FavoriteHeartIcon
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.utils.longPressToCopy
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * 演员 / 导演作品页：点详情页演职员头像进来，按 PersonIds 查 TA 参与过的条目。
 *
 * Emby 的 /Items 支持 PersonIds 过滤，所以这里不需要额外的「按名字搜」逻辑，
 * 直接用详情页拿到的 PersonId 查，结果精确。
 */
@Serializable
data class EmbyPersonScreen(
  val personId: String,
  val personName: String,
  /** 演员头像的 image tag；空则显示占位人形图标 */
  val personImageTag: String? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: EmbyViewModel = viewModel(
      factory = EmbyViewModel.factory(context.applicationContext as Application),
    )
    val server by viewModel.currentServer.collectAsState()
    val scope = rememberCoroutineScope()
    // 随机播放数量上限读用户设置，与媒体库的随机按钮同一口径
    val browserPreferences = koinInject<BrowserPreferences>()

    var items by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 演员本人的条目（含 UserData.IsFavorite）：右上角收藏红心的状态来源。
    // 进页面查一次；收藏/取消收藏成功后本地翻转，不再回查服务器。
    var personItem by remember { mutableStateOf<EmbyItem?>(null) }
    // 是否完成过一次加载：ON_RESUME 刷新只在「已经加载过」时才做，避免首次进入重复拉取
    var hasLoaded by remember { mutableStateOf(false) }

    // ── 作品聚合 / 筛选（纯本地，切 tab 不发请求）──
    // 作品类型 tab：全部 / 电影 / 剧集 / 单集（按条目 Type 聚合）
    var itemTab by remember { mutableStateOf(PersonItemTab.ALL) }
    // 参与身份筛选：全部 / 演员 / 导演 / 编剧（按条目自带的 People 列表本地筛）
    var roleFilter by remember { mutableStateOf(PersonRoleFilter.ALL) }
    val visibleItems = items.filter { itemTab.matches(it) && roleFilter.matches(it, personId) }

    // 「参与身份」只在**确实观察到**该身份时才提供对应 chip：
    // 一个只演戏、从不导也不写的人，作品页再挂「导演 / 编剧」两个 chip，点下去永远是空 ——
    // 既没用又让人以为数据缺了。这里根据已加载作品的 People 列表算出 TA 真实拥有的身份。
    // ⚠️ 完全拿不到 People 数据时（部分服务端不返回该字段）退回「全部显示」，
    // 与 PersonRoleFilter.matches 的宽松口径一致：不确定就都给，不制造假空白。
    val availableRoles =
      remember(items, personId) {
        val observed =
          items.asSequence()
            .flatMap { it.People.orEmpty().asSequence() }
            .filter { it.Id == personId }
            .mapNotNull { it.Type?.lowercase() }
            .toSet()
        PersonRoleFilter.entries.filter { role ->
          if (observed.isEmpty()) {
            true
          } else {
            role.apiType == null || observed.contains(role.apiType.lowercase())
          }
        }
      }
    // 选中的身份若因数据变化不再可用（例如只演不导的人不再显示「导演」chip），
    // 自动落回「全部」—— 否则会停在一个已隐藏的筛选上，看到一片空白。
    LaunchedEffect(availableRoles) {
      if (roleFilter !in availableRoles) roleFilter = PersonRoleFilter.ALL
    }

    // 本页是「演员 / 导演 / 编剧」共用的，界面上的称呼要跟着 TA 真实拥有的身份走：
    // 只导不演的人不该被叫「演员」。多身份或拿不到 People 时退回中性的「人物」。
    val personLabel =
      remember(availableRoles) {
        val concrete = availableRoles.filter { it != PersonRoleFilter.ALL }
        if (concrete.size == 1) concrete[0].label else "人物"
      }

    /**
     * 随机播放这位演员 / 导演的作品：把**当前筛选出的**列表打乱后丢给主播放器排队连播。
     *
     * 取「当前可见列表」而不是全量：用户筛了「只看电影」或某个身份，随机播放就该只在这些
     * 里抽，否则点下去放出的是被筛掉的内容，跟眼前对不上。数量上限读用户设置（与媒体库的
     * 随机按钮同一口径）。随机出的列表里常混着看过的剧，所以每条都从头放
     * （playFromStartAll = true），避免切集时恢复进度直接跳到片尾。
     */
    fun startRandomPlayback() {
      val current = server ?: return
      val limit = browserPreferences.randomPlayCount.get().coerceIn(1, 500)
      val pool = visibleItems.shuffled().take(limit)
      if (pool.isEmpty()) {
        Toast.makeText(context, "当前筛选下没有可播放的作品", Toast.LENGTH_SHORT).show()
        return
      }
      viewModel.launchPlaylist(current, pool, playFromStartAll = true)
    }

    suspend fun load() {
      val current = server
      if (current == null) return
      isLoading = true
      error = null
      // 网格 key = Id ?: Name：按同一口径去重，防止服务端重复条目把网格撞崩
      val list = viewModel.loadPersonItems(current, personId).distinctBy { it.Id ?: it.Name ?: "" }
      items = list
      isLoading = false
      hasLoaded = true
      if (list.isEmpty()) error = "没有找到「$personName」的作品"
    }

    LaunchedEffect(personId, server) {
      load()
      // 红心状态单独拉。**必须保证拿到可点击的条目**：查询失败（服务端不支持等）
      // 就用 Id + 名字拼一个最小条目顶上 —— 否则红心永远禁用，点了没任何反馈。
      // UserData 未知时按「未收藏」处理，第一次点击会执行收藏，语义无损。
      val current = server ?: return@LaunchedEffect
      personItem =
        viewModel.loadPersonById(current, personId)
          ?: EmbyItem(Id = personId, Name = personName, Type = "Person")
    }

    // 回到本页时刷新列表：从「演职员合并」工具页 / 详情页返回时，本页只是从返回栈恢复，
    // 上面的 LaunchedEffect 不会重跑 —— 合并把作品挪给保留项之后不重拉的话，
    // 看到的还是离开前的旧数据（「明明合并了却还是旧的」多半是这个）。
    val personLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(personLifecycleOwner, personId, server) {
      val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && hasLoaded) {
          scope.launch { load() }
        }
      }
      personLifecycleOwner.lifecycle.addObserver(observer)
      onDispose { personLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
      TopAppBar(
        title = {
          Text(
            text = personName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.longPressToCopy(personName, "姓名"),
          )
        },
        navigationIcon = {
          IconButton(onClick = { backStack.removeLastOrNull() }) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "返回",
            )
          }
        },
        actions = {
          // ── 随机播放这位演员 / 导演的作品（在当前筛选范围内打乱连播）──
          IconButton(
            enabled = visibleItems.isNotEmpty(),
            onClick = { startRandomPlayback() },
          ) {
            Icon(
              imageVector = Icons.Default.Shuffle,
              contentDescription = "随机播放作品",
            )
          }
          // ── 右上角收藏红心 ──
          // 与详情页同一套交互：乐观更新红心 → 请求服务器 → 按结果修正并弹 Toast；
          // 动效复用 FavoriteHeartIcon（弹跳 + 星光 + 红心渐变）。
          val isFavorite = personItem?.UserData?.IsFavorite == true
          IconButton(
            enabled = personItem != null,
            onClick = {
              val current = personItem ?: return@IconButton
              val currentServer = server ?: return@IconButton
              val wasFavorite = current.UserData?.IsFavorite == true
              // 乐观更新：先把红心翻过来，动效才跟得上手指；失败再回滚
              personItem = current.copy(
                UserData = (current.UserData ?: EmbyUserData()).copy(IsFavorite = !wasFavorite),
              )
              scope.launch {
                val result = viewModel.toggleFavorite(currentServer, current)
                val nowFavorite = result.getOrNull()
                personItem = current.copy(
                  UserData = (current.UserData ?: EmbyUserData())
                    .copy(IsFavorite = nowFavorite ?: wasFavorite),
                )
                Toast.makeText(
                  context,
                  when (nowFavorite) {
                    true -> "已收藏「$personName」"
                    false -> "已取消收藏「$personName」"
                    null -> "收藏失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                  },
                  Toast.LENGTH_SHORT,
                ).show()
              }
            },
          ) {
            // 与媒体详情页同款：弹跳 + 星光 + 红心渐变（状态驱动，点击时先乐观更新）
            // 尺寸对齐详情页的 24dp，避免「顶栏这颗比详情页小一圈」的观感落差
            FavoriteHeartIcon(
              isFavorite = isFavorite,
              isToggling = false,
              iconSize = 24.dp,
              idleColor = LocalContentColor.current,
            )
          }
        },
      )

      // ── 头部：演员头像 + 名字 ──
      val avatarUrl = remember(personId, personImageTag, server) {
        server?.let { EmbyClient.imageUrl(it, personId, "Primary", personImageTag, 300) }
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.size(72.dp),
        ) {
          EmbyImage(
            url = avatarUrl,
            contentDescription = personName,
            modifier = Modifier
              .fillMaxSize()
              .clip(CircleShape),
            maxWidth = 300,
            placeholder = Icons.Default.Person,
          )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
          Text(
            text = personName,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.longPressToCopy(personName, "姓名"),
          )
          Text(
            text = if (isLoading) "加载中…" else "共 ${visibleItems.size} 部作品",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          // 人物 ID：排查「同名分裂成多条 Person」问题时最有用的信息 ——
          // 服务器上看是同一个人，App 里可能是两个不同的 Id。长按复制。
          // 前缀用 personLabel（演员 / 导演 / 编剧 / 人物），不写死成「演员」。
          Text(
            text = "$personLabel ID：$personId",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.longPressToCopy(personId, "$personLabel ID"),
          )
        }
      }

      // 类型 / 身份两组筛选 chips：只过滤本页已加载的数据，切 tab 不发请求
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        FilterChipRow(
          options = PersonItemTab.entries,
          selected = itemTab,
          label = { it.label },
          onSelect = { itemTab = it },
        )
        FilterChipRow(
          // 只列出这个人真实拥有的身份（见 availableRoles）：只演戏的人不再出现「导演 / 编剧」
          options = availableRoles,
          selected = roleFilter,
          label = { it.label },
          onSelect = { roleFilter = it },
        )
      }

      Box(modifier = Modifier.fillMaxSize()) {
        when {
          isLoading && items.isEmpty() -> {
            // 一行三列海报卡，和下面真实结果的版式对齐
            EmbySkeletonGrid(columns = 3, ratio = 3f / 4f)
          }

          error != null && items.isEmpty() -> {
            Text(
              text = error ?: "",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            )
          }

          // 有数据但被当前筛选条件滤空：给一句提示，别让用户以为加载失败
          visibleItems.isEmpty() -> {
            Text(
              text = "这个筛选条件下没有作品",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            )
          }

          else -> {
            LazyVerticalGrid(
              // 与媒体库「演员」分类、收藏页同一套规格：固定一行三列海报卡
              // （原先用 Adaptive(110dp)，与其它入口的卡片大小对不上，用户反馈已统一）
              columns = GridCells.Fixed(3),
              contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxSize(),
            ) {
              items(visibleItems, key = { it.Id ?: it.Name ?: "" }) { item ->
                EmbyMediaCard(
                  title = item.Name ?: "",
                  subtitle = item.ProductionYear?.toString(),
                  imageUrl = server?.let { viewModel.imageUrl(it, item, "Primary", 400) },
                  progress = null,
                  isFavorite = item.UserData?.IsFavorite == true,
                  fillWidth = true,
                  onClick = {
                    item.Id?.let { id ->
                      backStack.add(EmbyDetailScreen(itemId = id, title = item.Name ?: ""))
                    }
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * 作品类型聚合 tab（本地过滤，不改变服务器查询）。
 *
 * 演员作品清单里 Movie / Series / Episode 常常混在一起（尤其是剧集演员），
 * 所以按 Type 拆成几个 tab，用户想只看电影或只看剧集时一键切换。
 */
private enum class PersonItemTab(val label: String) {
  ALL("全部"),
  MOVIE("电影"),
  SERIES("剧集"),
  EPISODE("单集"),
  ;

  fun matches(item: EmbyItem): Boolean = when (this) {
    ALL -> true
    MOVIE -> item.Type.equals("Movie", ignoreCase = true)
    SERIES -> item.Type.equals("Series", ignoreCase = true)
    EPISODE -> item.Type.equals("Episode", ignoreCase = true)
  }
}

/**
 * 「参与身份」筛选：同一部片里这个人可能是主演、也可能是导演 / 编剧，
 * 按 [apiType]（Emby 的 People.Type 英文值）过滤条目的 People 列表。
 *
 * ⚠️ People 缺失时**不过滤**（返回 true）：服务端没返回 People 字段的情况真实存在，
 * 若此时一律判 false，用户切到「导演」会看到空白页、误以为没有作品。
 * 宁可宽松（多显示几条），也不要制造「筛选后没东西」的假象。
 */
private enum class PersonRoleFilter(val label: String, val apiType: String?) {
  ALL("全部", null),
  ACTOR("演员", "Actor"),
  DIRECTOR("导演", "Director"),
  WRITER("编剧", "Writer"),
  ;

  fun matches(item: EmbyItem, personId: String): Boolean {
    val type = apiType ?: return true
    val people = item.People ?: return true
    val self = people.filter { it.Id == personId }
    if (self.isEmpty()) return true
    return self.any { it.Type.equals(type, ignoreCase = true) }
  }
}

/** 一行可横向滚动的单选 FilterChip，类型 / 身份两组筛选共用。 */
@Composable
private fun <T> FilterChipRow(
  options: List<T>,
  selected: T,
  label: (T) -> String,
  onSelect: (T) -> Unit,
) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    items(options) { option ->
      FilterChip(
        selected = option == selected,
        onClick = { onSelect(option) },
        label = { Text(label(option)) },
      )
    }
  }
}
