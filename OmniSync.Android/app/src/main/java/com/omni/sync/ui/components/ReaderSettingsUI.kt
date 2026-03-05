package com.omni.sync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.utils.ReaderTheme

@Composable
fun ReaderSettingsOverlay(
    theme: ReaderTheme,
    onThemeChange: (ReaderTheme) -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reader Settings", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Color Presets
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ColorPreset(
                    bg = "#FFFFFF", text = "#111111", 
                    isSelected = theme.backgroundColor == "#FFFFFF",
                    onClick = { onThemeChange(theme.copy(backgroundColor = "#FFFFFF", textColor = "#111111")) }
                )
                ColorPreset(
                    bg = "#F4ECD8", text = "#5B4636", // Sepia
                    isSelected = theme.backgroundColor == "#F4ECD8",
                    onClick = { onThemeChange(theme.copy(backgroundColor = "#F4ECD8", textColor = "#5B4636")) }
                )
                ColorPreset(
                    bg = "#111111", text = "#CCCCCC", // Dark
                    isSelected = theme.backgroundColor == "#111111",
                    onClick = { onThemeChange(theme.copy(backgroundColor = "#111111", textColor = "#CCCCCC")) }
                )
                ColorPreset(
                    bg = "#000000", text = "#FFFFFF", // High Contrast
                    isSelected = theme.backgroundColor == "#000000",
                    onClick = { onThemeChange(theme.copy(backgroundColor = "#000000", textColor = "#FFFFFF")) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Font Size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FormatSize, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Font Size", style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = theme.fontSize.toFloat(),
                onValueChange = { onThemeChange(theme.copy(fontSize = it.toInt())) },
                valueRange = 12f..32f,
                steps = 10
            )

            Spacer(modifier = Modifier.height(8.dp))

            // PDF Invert
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Invert PDF Colors (Image-based)", style = MaterialTheme.typography.labelLarge)
                Switch(
                    checked = theme.invertPdf,
                    onCheckedChange = { onChecked -> onThemeChange(theme.copy(invertPdf = onChecked)) }
                )
            }
        }
    }
}

@Composable
fun ColorPreset(bg: String, text: String, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor = Color(android.graphics.Color.parseColor(bg))
    val textColor = Color(android.graphics.Color.parseColor(text))
    
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text("A", color = textColor, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
