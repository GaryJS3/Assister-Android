package net.thegary.assister.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF12695D), onPrimary = Color.White,
    background = Color(0xFFF8F6F0), surface = Color(0xFFF8F6F0),
    surfaceVariant = Color(0xFFE9EDE5), onSurfaceVariant = Color(0xFF34483F),
    onBackground = Color(0xFF172E28), onSurface = Color(0xFF172E28)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF88D5BE), background = Color(0xFF101C19), surface = Color(0xFF101C19),
    surfaceVariant = Color(0xFF24352E), onSurfaceVariant = Color(0xFFD5E5DA),
    onBackground = Color(0xFFE1EEE7), onSurface = Color(0xFFE1EEE7)
)

@Composable
fun AssisterTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typography, content = content)
}
