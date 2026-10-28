package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val mobileTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun MobileTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = choice == ThemeChoice.DARK || (choice == ThemeChoice.SYSTEM && isSystemInDarkTheme())
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFFA9C7FF),
            onPrimary = Color(0xFF002D6F),
            background = Color.Black,
            surface = Color(0xFF18181B),
            secondaryContainer = Color(0xFF203456),
            onSecondaryContainer = Color(0xFFD7E4FF),
            surfaceVariant = Color(0xFF242428),
            onSurfaceVariant = Color(0xFFB6B6BE),
            outlineVariant = Color(0xFF36363C),
            onBackground = Color(0xFFF4F4F7),
            onSurface = Color(0xFFF4F4F7)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF185CC4),
            onPrimary = Color.White,
            background = Color(0xFFF2F2F7),
            surface = Color.White,
            secondaryContainer = Color(0xFFE5EDFC),
            onSecondaryContainer = Color(0xFF153A73),
            surfaceVariant = Color(0xFFEAEAF0),
            onSurfaceVariant = Color(0xFF61616A),
            outlineVariant = Color(0xFFDFDFE6),
            onBackground = Color(0xFF19191D),
            onSurface = Color(0xFF19191D)
        )
    }
    MaterialTheme(colorScheme = colors, typography = mobileTypography, content = content)
}
