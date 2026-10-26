package org.kundakarlab.nimsresults

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Constant-work fast scrollbar for very long NIMS results lists.
 *
 * Never launches a scrolling coroutine per pointer delta. requestScrollToItem
 * schedules one layout request; repeated drags coalesce on the UI pipeline.
 * A list item can be a variable-height clinical report so the thumb is an
 * approximate index locator, not an exact pixel-measured progress indicator.
 */
@Composable
fun DraggableResultsScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val entries = info.visibleItemsInfo
    val count = info.totalItemsCount
    if ((!state.canScrollForward && !state.canScrollBackward) ||
        count < 2 || entries.isEmpty()) return

    val density = LocalDensity.current
    val visible = entries.size.coerceAtLeast(1)
    val fraction = (state.firstVisibleItemIndex.toFloat() /
        (count - 1).coerceAtLeast(1)).coerceIn(0f, 1f)

    BoxWithConstraints(modifier.width(22.dp).fillMaxHeight()) {
        val trackPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val rawSize = visible.toFloat() / count
        val thumbHeight = (maxHeight * rawSize).coerceAtLeast(44.dp).coerceAtMost(maxHeight)
        val thumbPx = with(density) { thumbHeight.toPx() }
        val travelPx = (trackPx - thumbPx).coerceAtLeast(1f)
        val travel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        var dragPosition by remember(count, trackPx) { mutableFloatStateOf(-1f) }
        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(count, trackPx) {
                    detectDragGestures(
                        onDragStart = { start ->
                            dragPosition = (start.y - thumbPx / 2f)
                                .coerceIn(0f, travelPx)
                        },
                        onDragEnd = { dragPosition = -1f },
                        onDragCancel = { dragPosition = -1f },
                        onDrag = { change, delta ->
                            change.consume()
                            dragPosition = (dragPosition + delta.y)
                                .coerceIn(0f, travelPx)
                            val target = ((count - 1) * dragPosition / travelPx)
                                .toInt().coerceIn(0, count - 1)
                            state.requestScrollToItem(target)
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
                Modifier.offset(y = travel * if (dragPosition < 0f) fraction
                    else (dragPosition / travelPx))
                    .width(9.dp).height(thumbHeight)
                    .background(Color(0xFF537B9A).copy(alpha = 0.85f),
                        RoundedCornerShape(8.dp))
            )
        }
    }
}
