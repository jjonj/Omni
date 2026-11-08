package com.omni.sync.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.omni.sync.viewmodel.AppScreen
import kotlin.math.abs

/** The navigation order is a single list; its first N entries are the bottom bar. */
@Composable
fun NavigationOrderEditor(
    savedOrder: List<String>?,
    savedVisibleCount: Int,
    onOrderChange: (List<String>) -> Unit,
    onVisibleCountChange: (Int) -> Unit
) {
    var items by remember(savedOrder) { mutableStateOf(configuredNavigationItems(savedOrder)) }
    val visibleCount = configuredVisibleCount(savedVisibleCount)
    val rowStepPx = with(LocalDensity.current) { 52.dp.toPx() }
    var dragging by remember { mutableStateOf<AppScreen?>(null) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var expanded by remember { mutableStateOf(false) }

    Text(
        "Bottom bar screens",
        style = MaterialTheme.typography.titleSmall
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Always visible", modifier = Modifier.weight(1f))
        IconButton(
            onClick = { onVisibleCountChange(visibleCount - 1) },
            enabled = visibleCount > 1
        ) { Icon(Icons.Default.Remove, contentDescription = "Show one fewer screen") }
        Text("$visibleCount / ${items.size}", style = MaterialTheme.typography.titleSmall)
        IconButton(
            onClick = { onVisibleCountChange(visibleCount + 1) },
            enabled = visibleCount < items.size
        ) { Icon(Icons.Default.Add, contentDescription = "Show one more screen") }
    }
    Text(
        "Bar: " + items.take(visibleCount).joinToString(" · ") { it.title },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide screen order" else "Reorder screens")
    }
    if (expanded) {
        Text(
            "Long-press a handle and drag. Items below the first $visibleCount are in More.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }

    if (expanded) items.forEachIndexed { index, item ->
        key(item.screen) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (dragging == item.screen) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (index < visibleCount) "Bar" else "More",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier.size(44.dp).pointerInput(item.screen, rowStepPx) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragging = item.screen
                                    dragDistance = 0f
                                },
                                onDragEnd = {
                                    dragging = null
                                    onOrderChange(items.map { it.screen.name })
                                },
                                onDragCancel = {
                                    dragging = null
                                    onOrderChange(items.map { it.screen.name })
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    dragDistance += drag.y
                                    // Swap adjacent screens as the handle crosses each row.
                                    while (abs(dragDistance) >= rowStepPx) {
                                        val direction = if (dragDistance > 0f) 1 else -1
                                        val from = items.indexOfFirst { it.screen == item.screen }
                                        val to = from + direction
                                        if (to !in items.indices) {
                                            dragDistance = 0f
                                            break
                                        }
                                        items = items.toMutableList().apply {
                                            add(to, removeAt(from))
                                        }
                                        dragDistance -= direction * rowStepPx
                                    }
                                }
                            )
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.DragHandle, contentDescription = "Reorder ${item.title}")
                    }
                }
            }
        }
    }
}
