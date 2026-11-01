package com.leaf.reader.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun LeafTheme(dark: Boolean = true, accent: Color = Color(0xFFA5C7A7), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color(0xFF15371E),
        background = Color(0xFF111512), surface = Color(0xFF171D18),
        surfaceVariant = Color(0xFF263029), onBackground = Color(0xFFE5E8DD),
        onSurface = Color(0xFFE5E8DD), secondary = Color(0xFFC4CABA)
    ) else lightColorScheme(primary = Color(0xFF3A6845), background = Color(0xFFFAFBF4), surface = Color(0xFFF5F7EF))
    MaterialTheme(colorScheme = colors, content = content)
}
