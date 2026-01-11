package com.omni.sync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun ActionKeyButton(
    modifier: Modifier = Modifier, 
    icon: ImageVector? = null, 
    text: String? = null, 
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { onLongClick() },
                onTap = { onClick() }
            )
        }
    } else {
        Modifier.clickable { onClick() }
    }

    Surface(
        modifier = modifier
            .height(40.dp)
            .then(clickModifier),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) Icon(icon, contentDescription = text, modifier = Modifier.size(18.dp))
            if (icon != null && text != null) Spacer(Modifier.width(4.dp))
            if (text != null) Text(text ?: "", maxLines = 1, overflow = TextOverflow.Visible, fontSize = 11.sp)
        }
    }
}

@Composable
fun VerticalScrollbar(
    modifier: Modifier = Modifier,
    state: LazyListState
) {
    val coroutineScope = rememberCoroutineScope()
    
    // Scrollbar logic
    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val totalItems = state.layoutInfo.totalItemsCount
        if (totalItems == 0) return@BoxWithConstraints
        
        val visibleItems = state.layoutInfo.visibleItemsInfo.size
        if (visibleItems >= totalItems) return@BoxWithConstraints

        val scrollbarHeight = maxHeight
        val thumbHeight = (scrollbarHeight * visibleItems / totalItems).coerceAtLeast(40.dp)
        
        val firstVisibleIndex = state.firstVisibleItemIndex
        val firstVisibleOffset = state.firstVisibleItemScrollOffset
        
        // Approximate current scroll percentage
        val scrollPercent = if (totalItems > visibleItems) {
            firstVisibleIndex.toFloat() / (totalItems - visibleItems)
        } else 0f
        
        val thumbOffset = (scrollbarHeight - thumbHeight) * scrollPercent

        Box(
            modifier = Modifier
                .offset(y = thumbOffset)
                .width(8.dp)
                .height(thumbHeight)
                .background(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(4.dp)
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val deltaPercent = dragAmount.y / (scrollbarHeight.toPx() - thumbHeight.toPx())
                        val targetIndex = ((firstVisibleIndex + (totalItems - visibleItems) * deltaPercent)).toInt()
                            .coerceIn(0, totalItems - 1)
                        
                        coroutineScope.launch {
                            state.scrollToItem(targetIndex)
                        }
                    }
                }
        )
    }
}
