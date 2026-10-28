package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The Android TaskBoard intentionally shares the desktop's blue-slate palette
 * rather than inheriting Android's dynamic wallpaper / purple Material palette.
 * Scoped to TaskBoard so the other OmniSync screens keep their existing theme.
 */
internal object TaskBoardColors {
    val background = Color(0xFF111923)
    val surface = Color(0xFF1B2632)
    val card = Color(0xFF263442)
    val raised = Color(0xFF304154)
    val border = Color(0xFF3C5165)
    val muted = Color(0xFF9FB0C1)
    val text = Color(0xFFE9EFF5)
    val blue = Color(0xFFA2D1F4)
    val blueDark = Color(0xFF314D68)
    val green = Color(0xFF9ADBB8)
    val greenDark = Color(0xFF28483A)
    val amber = Color(0xFFF2C77C)
    val amberDark = Color(0xFF493927)
    val purple = Color(0xFFCCB9F4)
    val purpleDark = Color(0xFF423952)
    val red = Color(0xFFFFAEA9)
}

private val taskBoardColorScheme = darkColorScheme(
    primary = TaskBoardColors.blue,
    onPrimary = Color(0xFF122333),
    primaryContainer = TaskBoardColors.blueDark,
    onPrimaryContainer = TaskBoardColors.text,
    secondary = TaskBoardColors.green,
    onSecondary = TaskBoardColors.background,
    secondaryContainer = TaskBoardColors.greenDark,
    onSecondaryContainer = TaskBoardColors.text,
    tertiary = TaskBoardColors.amber,
    onTertiary = TaskBoardColors.background,
    tertiaryContainer = TaskBoardColors.amberDark,
    onTertiaryContainer = TaskBoardColors.text,
    background = TaskBoardColors.background,
    onBackground = TaskBoardColors.text,
    surface = TaskBoardColors.surface,
    onSurface = TaskBoardColors.text,
    surfaceVariant = TaskBoardColors.card,
    onSurfaceVariant = TaskBoardColors.muted,
    outline = TaskBoardColors.border,
    outlineVariant = Color(0xFF2D3A47),
    error = TaskBoardColors.red,
    errorContainer = Color(0xFF522F32),
    onErrorContainer = Color(0xFFF6D9D8)
)

@Composable
internal fun TaskBoardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = taskBoardColorScheme, content = content)
}

@Composable
internal fun TaskBoardBadge(
    label: String,
    accent: Color = TaskBoardColors.blue,
    background: Color = TaskBoardColors.blueDark,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.border(1.dp, accent.copy(alpha = .24f), RoundedCornerShape(5.dp)),
        shape = RoundedCornerShape(5.dp),
        color = background,
        contentColor = accent
    ) {
        androidx.compose.material3.Text(
            label.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
