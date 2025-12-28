package com.omni.sync.ui.components

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
