@file:Suppress("ktlint:standard:no-wildcard-imports")

package app.marlboroadvance.mpvex.ui.player.controls.components.panels

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.marlboroadvance.mpvex.ui.player.controls.panelCardsColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

import kotlin.math.roundToInt

/**
 * A draggable panel with an optional fixed header and scrollable content.
 *
 * @param modifier Modifier for the panel
 * @param header Optional composable for the fixed header that stays constant during scroll
 * @param scrollable 是否由本组件负责纵向滚动。**内容自带 `LazyColumn` 时必须传 false**：
 *   外面再套一层 `verticalScroll` 会让 LazyColumn 拿到无限高约束，直接抛
 *   `Vertically scrollable component was measured with an infinity maximum height constraints`。
 * @param heightFraction 面板高度占「可用高度」的比例；null = 随内容收缩（旧行为）。
 *   需要内部滚动列表的面板必须给值 —— 没有确定高度，列表既画不满也不会有滚动条。
 * @param content The content of the panel（给了 `ColumnScope` receiver，内部可用 `weight`）
 */
@Composable
fun DraggablePanel(
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    heightFraction: Float? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var panelWidth by remember { mutableIntStateOf(0) }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (isPortrait) Alignment.Center else Alignment.CenterEnd
    ) {
        val density = LocalDensity.current
        val parentWidthPx = with(density) { maxWidth.toPx() }
        
        // Calculate bounds for horizontal drag
        // Panel is aligned to CenterEnd (Right), so offset 0 is the default rightmost position.
        val freeSpace = (parentWidthPx - panelWidth).coerceAtLeast(0f)
        val maxOffset = 0f
        val minOffset = -freeSpace

        // In portrait, cap panel height to 50% of available height
        val panelMaxHeight = if (isPortrait) maxHeight * 0.5f else maxHeight
        // 给了比例就固定高度：内部有 LazyColumn 时需要确定高度才能滚动
        val fixedHeight = heightFraction?.let { panelMaxHeight * it }

        val colors = panelCardsColors()
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .onSizeChanged { panelWidth = it.width }
                .widthIn(max = 380.dp)
                .then(
                    if (fixedHeight != null) {
                        Modifier.height(fixedHeight)
                    } else {
                        Modifier.heightIn(max = panelMaxHeight)
                    }
                ),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.containerColor,
            contentColor = colors.contentColor,
            tonalElevation = 0.dp,
        ) {
            Column {
                 // Drag Handle & Indicator
                 Box(
                     modifier = Modifier
                         .fillMaxWidth()
                         .height(18.dp) // Good touch target size
                         .pointerInput(maxOffset, minOffset) {
                             detectDragGestures { change, dragAmount ->
                                 change.consume()
                                 val newOffset = offsetX + dragAmount.x
                                 offsetX = newOffset.coerceIn(minOffset, maxOffset)
                             }
                         },
                     contentAlignment = Alignment.Center
                 ) {
                     Box(
                         modifier = Modifier
                             .width(32.dp)
                             .height(4.dp)
                             .background(
                                 color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                 shape = RoundedCornerShape(2.dp)
                             )
                     )
                 }
                
                // Fixed header (if provided) - stays constant
                if (header != null) {
                    header()
                }
                
                // Scrollable content
                if (scrollable) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        content()
                    }
                } else {
                    // 内容自己滚（LazyColumn）：吃掉头部剩下的高度即可
                    Column(modifier = Modifier.fillMaxHeight()) {
                        content()
                    }
                }
            }
        }
    }
}
