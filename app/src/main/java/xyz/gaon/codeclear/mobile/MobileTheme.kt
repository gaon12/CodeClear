package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun MobileTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = choice == ThemeChoice.DARK || (choice == ThemeChoice.SYSTEM && isSystemInDarkTheme())
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFFFFB59C),
            onPrimary = Color(0xFF54200D),
            background = Color.Black,
            surface = Color(0xFF151515),
            secondaryContainer = Color(0xFF4C3027),
            onSecondaryContainer = Color(0xFFFFD8C9),
            surfaceVariant = Color(0xFF292522),
            onBackground = Color(0xFFF4EEE9),
            onSurface = Color(0xFFF4EEE9)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF963D23),
            onPrimary = Color.White,
            background = Color(0xFFF7F6F3),
            surface = Color.White,
            secondaryContainer = Color(0xFFF7DDD1),
            onSecondaryContainer = Color(0xFF582412),
            surfaceVariant = Color(0xFFF0E9E3),
            onBackground = Color(0xFF242827),
            onSurface = Color(0xFF242827)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
