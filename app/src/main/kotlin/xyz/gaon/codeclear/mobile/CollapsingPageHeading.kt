package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.lerp
import androidx.compose.ui.unit.dp

/** Consume the header's travel before the list, so content follows the finger without jumping. */
internal class PageHeadingState(private val list: LazyListState, private val range: Float) :
    NestedScrollConnection {
    private var offset by mutableFloatStateOf(
        if (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) -range else 0f
    )
    val fraction: Float get() = if (range == 0f) 1f else -offset / range

    private fun consume(delta: Float): Offset {
        val next = (offset + delta).coerceIn(-range, 0f)
        val consumed = next - offset
        offset = next
        return Offset(0f, consumed)
    }
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val atTop = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
        return if (available.y < 0 || atTop) consume(available.y) else Offset.Zero
    }
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = if (available.y > 0) consume(available.y) else Offset.Zero
}

@Composable
internal fun rememberPageHeading(
    list: LazyListState,
    expandable: Boolean = true
): PageHeadingState {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val range = with(density) {
        if (expandable && configuration.screenWidthDp < 840 &&
            configuration.screenHeightDp >= 560
        ) {
            64.dp.toPx()
        } else {
            0f
        }
    }
    return remember(list, range) { PageHeadingState(list, range) }
}

@Composable
internal fun CollapsingPageHeading(
    title: String,
    state: PageHeadingState,
    titleTag: String = "destination-title"
) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = (128f - 64f * state.fraction).dp)
            .padding(horizontal = 24.dp, vertical = 12.dp).testTag("destination-heading-frame"),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            title,
            Modifier.testTag(titleTag).semantics { heading() },
            style = lerp(
                MaterialTheme.typography.displaySmall,
                MaterialTheme.typography.titleLarge,
                state.fraction
            ),
            fontWeight = FontWeight.SemiBold
        )
    }
}
