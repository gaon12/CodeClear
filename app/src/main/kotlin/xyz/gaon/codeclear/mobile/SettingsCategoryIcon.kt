package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
internal fun SettingsCategoryIcon(page: SettingsPage) {
    val (drawable, color) = when (page) {
        SettingsPage.CAPTURE -> R.drawable.ic_category_capture to Color(0xFF2F6EDD)

        SettingsPage.TRANSLATION -> R.drawable.ic_category_translation to Color(0xFF008A78)

        SettingsPage.APPEARANCE -> R.drawable.ic_category_appearance to Color(0xFF8D55C7)

        SettingsPage.NOTIFICATIONS -> R.drawable.ic_category_notifications to Color(0xFFBF6B00)

        SettingsPage.SHARING -> R.drawable.ic_category_sharing to Color(0xFF287D96)

        SettingsPage.LABS -> R.drawable.ic_category_labs to Color(0xFF6A59B5)

        SettingsPage.SERVER -> R.drawable.ic_category_translation to Color(0xFF008A78)

        SettingsPage.PRIVACY -> R.drawable.ic_category_privacy to Color(0xFF438047)

        SettingsPage.COMPATIBILITY, SettingsPage.ABOUT ->
            R.drawable.ic_category_about to
                Color(0xFF657187)
    }
    Surface(
        modifier = Modifier.testTag("settings-icon-${page.name}"),
        shape = CircleShape,
        color = color
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(drawable), null, Modifier.size(22.dp), tint = Color.White)
        }
    }
}
