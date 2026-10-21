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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Android Compose has no built-in draggable LazyColumn scrollbar.
 * A narrow touch rail maps thumb position to LazyListState item index.
 * Natural fling/regular finger scrolling remains unchanged.
 * Approximate positioning is intentional for variable-height result cards.
 */
@Composable
fun DraggableResultsScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val count = state.layoutInfo.totalItemsCount
    val visible = state.layoutInfo.visibleItemsInfo.size
    if (count <= 8 || visible <= 0 || count <= visible) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.width(24.dp).fillMaxHeight()) {
        val thumbHeight = (maxHeight * (visible.toFloat() / count))
            .coerceAtLeast(48.dp).coerceAtMost(maxHeight)
        val travel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        val maxIndex = (count - visible).coerceAtLeast(1)
        val fraction = (state.firstVisibleItemIndex.toFloat() / maxIndex)
            .coerceIn(0f, 1f)
        val travelPx = with(density) { travel.toPx() }

        fun goTo(pointerY: Float) {
            if (travelPx <= 0f) return
            val thumbPx = with(density) { thumbHeight.toPx() }
            val newFraction = ((pointerY - thumbPx / 2f) / travelPx).coerceIn(0f, 1f)
            val target = (newFraction * maxIndex).toInt().coerceIn(0, count - 1)
            scope.launch { state.scrollToItem(target) }
        }

        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(count, maxHeight) {
                    detectDragGestures(
                        onDragStart = { goTo(it.y) },
                        onDrag = { change, _ ->
                            change.consume()
                            goTo(change.position.y)
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
                    .background(Color(0xFF537B9A).copy(alpha = 0.88f),
                        RoundedCornerShape(8.dp))
            )
        }
    }
}
