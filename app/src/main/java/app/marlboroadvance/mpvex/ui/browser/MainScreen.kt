package app.marlboroadvance.mpvex.ui.browser

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.folderlist.FolderListScreen
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkStreamingScreen
import app.marlboroadvance.mpvex.ui.browser.playlist.PlaylistScreen
import app.marlboroadvance.mpvex.ui.browser.recentlyplayed.RecentlyPlayedScreen
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
object MainScreen : Screen {
  // Use a companion object to store state more persistently
  private var persistentSelectedTab: Int = 0

  // Reactive shared state that can be updated by FileSystemBrowserScreen / selection handlers
  private val _isInSelectionMode = MutableStateFlow(false)
  val isInSelectionMode: StateFlow<Boolean> = _isInSelectionMode.asStateFlow()

  private val _shouldHideNavigationBar = MutableStateFlow(false)
  val shouldHideNavigationBar: StateFlow<Boolean> = _shouldHideNavigationBar.asStateFlow()

  private val _sharedVideoSelectionManager = MutableStateFlow<Any?>(null)
  val sharedVideoSelectionManager: StateFlow<Any?> = _sharedVideoSelectionManager.asStateFlow()

  private val _isPermissionDenied = MutableStateFlow(false)

  /**
   * Update selection state and navigation bar visibility
   * This method should be called whenever selection changes
   */
  fun updateSelectionState(
    isInSelectionMode: Boolean,
    isOnlyVideosSelected: Boolean,
    selectionManager: Any?
  ) {
    _isInSelectionMode.value = isInSelectionMode
    _sharedVideoSelectionManager.value = selectionManager
    // Only hide navigation bar when videos are selected AND in selection mode
    _shouldHideNavigationBar.value = isInSelectionMode && isOnlyVideosSelected
  }

  /**
   * Update permission state to control FAB visibility
   */
  fun updatePermissionState(isDenied: Boolean) {
    _isPermissionDenied.value = isDenied
  }

  /**
   * Get current permission denied state
   */
  fun getPermissionDeniedState(): Boolean = _isPermissionDenied.value

  /**
   * Update bottom navigation bar visibility based on floating bottom bar state
   */
  fun updateBottomBarVisibility(shouldShow: Boolean) {
    _shouldHideNavigationBar.value = !shouldShow
  }

  @Composable
  @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
  override fun Content() {
    var selectedTab by remember {
      mutableIntStateOf(persistentSelectedTab)
    }

    val context = LocalContext.current
    val density = LocalDensity.current

    val hideNavigationBar by _shouldHideNavigationBar.collectAsState()

    // Update persistent state whenever tab changes
    LaunchedEffect(selectedTab) {
      persistentSelectedTab = selectedTab
    }

    // Scaffold with bottom navigation bar
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      bottomBar = {
        // Animated bottom navigation bar with slide animations
        AnimatedVisibility(
          visible = !hideNavigationBar,
          enter = slideInVertically(
            animationSpec = tween(durationMillis = 300),
            initialOffsetY = { fullHeight -> fullHeight }
          ),
          exit = slideOutVertically(
            animationSpec = tween(durationMillis = 300),
            targetOffsetY = { fullHeight -> fullHeight }
          )
        ) {
          NavigationBar(
            modifier = Modifier
              .clip(
                RoundedCornerShape(
                  topStart = 28.dp,
                  topEnd = 28.dp,
                  bottomStart = 0.dp,
                  bottomEnd = 0.dp
                )
              )
          ) {
            NavigationBarItem(
              icon = { Icon(Icons.Filled.Home, contentDescription = "首页") },
              label = { Text("首页") },
              selected = selectedTab == 0,
              onClick = { selectedTab = 0 }
            )
            NavigationBarItem(
              icon = { Icon(Icons.Filled.Favorite, contentDescription = "收藏") },
              label = { Text("收藏") },
              selected = selectedTab == 1,
              onClick = { selectedTab = 1 }
            )
            NavigationBarItem(
              icon = { Icon(Icons.Filled.History, contentDescription = "历史") },
              label = { Text("历史") },
              selected = selectedTab == 2,
              onClick = { selectedTab = 2 }
            )
            NavigationBarItem(
              icon = { Icon(Icons.Filled.Folder, contentDescription = "本地") },
              label = { Text("本地") },
              selected = selectedTab == 3,
              onClick = { selectedTab = 3 }
            )
            NavigationBarItem(
              icon = { Icon(Icons.Filled.Language, contentDescription = "网络") },
              label = { Text("网络") },
              selected = selectedTab == 4,
              onClick = { selectedTab = 4 }
            )
          }
        }
      }
    ) { paddingValues ->
      Box(modifier = Modifier.fillMaxSize()) {
        // Always use 80dp bottom padding regardless of navigation bar visibility
        val fabBottomPadding = 80.dp

        AnimatedContent(
          targetState = selectedTab,
          transitionSpec = {
            // Material 3 Expressive slide-in-fade animation (like Google Phone app)
            val slideDistance = with(density) { 48.dp.roundToPx() }
            val animationDuration = 250
            
            if (targetState > initialState) {
              // Moving forward: slide in from right with fade
              (slideInHorizontally(
                animationSpec = tween(
                  durationMillis = animationDuration,
                  easing = FastOutSlowInEasing
                ),
                initialOffsetX = { slideDistance }
              ) + fadeIn(
                animationSpec = tween(
                  durationMillis = animationDuration,
                  easing = FastOutSlowInEasing
                )
              )) togetherWith (slideOutHorizontally(
                animationSpec = tween(
                  durationMillis = animationDuration,
                  easing = FastOutSlowInEasing
                ),
                targetOffsetX = { -slideDistance }
              ) + fadeOut(
                animationSpec = tween(
                  durationMillis = animationDuration / 2,
                  easing = FastOutSlowInEasing
                )
              ))
            } else {
              // Moving backward: slide in from left with fade
              (slideInHorizontally(
                animationSpec = tween(
                  durationMillis = animationDuration,
                  easing = FastOutSlowInEasing
                ),
                initialOffsetX = { -slideDistance }
              ) + fadeIn(
                animationSpec = tween(
                  durationMillis = animationDuration,
                  easing = FastOutSlowInEasing
                )
              )) togetherWith (slideOutHorizontally(
                animationSpec = tween(
                  durationMillis = animationDuration,
                  easing = FastOutSlowInEasing
                ),
                targetOffsetX = { slideDistance }
              ) + fadeOut(
                animationSpec = tween(
                  durationMillis = animationDuration / 2,
                  easing = FastOutSlowInEasing
                )
              ))
            }
          },
          label = "tab_animation"
        ) { targetTab ->
          CompositionLocalProvider(
            LocalNavigationBarHeight provides fabBottomPadding
          ) {
            when (targetTab) {
              0 -> EmbyHomeRoute()
              1 -> app.marlboroadvance.mpvex.ui.browser.emby.EmbyFavoritesScreen()
              2 -> app.marlboroadvance.mpvex.ui.browser.emby.EmbyHistoryScreen()
              3 -> FolderListScreen.Content()
              4 -> NetworkStreamingScreen.Content()
            }
          }
        }
      }
    }
  }
}

/**
 * Emby 首页路由。
 *
 * 首页是 Emby 媒体库入口；媒体库、详情、服务器管理都通过导航栈 push 打开。
 */
@Composable
private fun EmbyHomeRoute() {
  val backStack = app.marlboroadvance.mpvex.ui.utils.LocalBackStack.current
  app.marlboroadvance.mpvex.ui.browser.emby.EmbyHomeScreen(
    onOpenLibrary = { library ->
      backStack.add(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyLibraryScreen(
          libraryId = library.Id ?: return@EmbyHomeScreen,
          title = library.Name ?: "",
          collectionType = library.CollectionType,
        ),
      )
    },
    onOpenDetail = { item ->
      backStack.add(
        app.marlboroadvance.mpvex.ui.browser.emby.EmbyDetailScreen(
          itemId = item.Id ?: return@EmbyHomeScreen,
          title = item.Name ?: "",
        ),
      )
    },
    onManageServers = { backStack.add(app.marlboroadvance.mpvex.ui.browser.emby.EmbyServerManageScreen) },
    onOpenSettings = { backStack.add(app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen) },
  )
}

// CompositionLocal for navigation bar height
val LocalNavigationBarHeight = compositionLocalOf { 0.dp }