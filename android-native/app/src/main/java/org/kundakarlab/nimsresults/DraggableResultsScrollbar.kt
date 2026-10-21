package org.kundakarlab.nimsresults

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Manual drag scrollbar for variable-height LazyColumn cards, including
 * a single long investigation (item count alone is not a scroll indicator).
 * Uses a measured-height estimate; clinical content is never truncated.
 */
@Composable
fun DraggableResultsScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val entries = info.visibleItemsInfo
    val count = info.totalItemsCount
    val canScroll = state.canScrollForward || state.canScrollBackward
    if (!canScroll || entries.isEmpty()) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val averageItem = entries.map { it.size }.average().coerceAtLeast(1.0)
    val estimateTotal = (averageItem * count).coerceAtLeast(viewport.toDouble() + 1.0)
    val scrollablePixels = (estimateTotal - viewport).coerceAtLeast(1.0)
    val currentPixels = (state.firstVisibleItemIndex * averageItem +
        state.firstVisibleItemScrollOffset).coerceAtLeast(0.0)
    val fraction = (currentPixels / scrollablePixels).toFloat().coerceIn(0f, 1f)

    BoxWithConstraints(modifier.width(24.dp).fillMaxHeight()) {
        val thumbHeight = (maxHeight * (viewport / estimateTotal).toFloat())
            .coerceAtLeast(46.dp).coerceAtMost(maxHeight)
        val travel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        val trackPixels = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(count, maxHeight) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            val distance = (delta.y / trackPixels *
                                scrollablePixels).toFloat()
                            scope.launch { state.scrollBy(distance) }
                        }
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .background(Color(0xFFE0E7EF), RoundedCornerShape(6.dp))
            )
            Box(
                Modifier.offset(y = travel * fraction)
                    .width(9.dp).height(thumbHeight)
                    .background(Color(0xFF537B9A).copy(alpha = 0.9f),
                        RoundedCornerShape(8.dp))
            )
        }
    }
}
