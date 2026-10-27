package xyz.gaon.codeclear.probe

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PreviewLight = lightColorScheme(
    primary = Color(0xFFA8431B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE9DD),
    onPrimaryContainer = Color(0xFF713011),
    secondaryContainer = Color(0xFFFFE9DD),
    onSecondaryContainer = Color(0xFF713011),
    background = Color(0xFFF8F7F4),
    onBackground = Color(0xFF262725),
    surface = Color(0xFFFFFEFC),
    onSurface = Color(0xFF262725),
    surfaceContainer = Color(0xFFF0EFEB),
    surfaceContainerLow = Color(0xFFF5F4F0),
    onSurfaceVariant = Color(0xFF62645E),
    outlineVariant = Color(0xFFE1E1DB)
)

private val PreviewDark = darkColorScheme(
    primary = Color(0xFFFFB58D),
    onPrimary = Color(0xFF562006),
    primaryContainer = Color(0xFF573322),
    onPrimaryContainer = Color(0xFFFFDBC7),
    secondaryContainer = Color(0xFF573322),
    onSecondaryContainer = Color(0xFFFFDBC7),
    background = Color(0xFF171916),
    onBackground = Color(0xFFE7E9E1),
    surface = Color(0xFF20231F),
    onSurface = Color(0xFFE7E9E1),
    surfaceContainer = Color(0xFF2A2E28),
    surfaceContainerLow = Color(0xFF242822),
    onSurfaceVariant = Color(0xFFBDC4B7),
    outlineVariant = Color(0xFF41473D)
)

@Composable
fun PreviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) PreviewDark else PreviewLight,
        content = content
    )
}
