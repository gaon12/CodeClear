package xyz.gaon.codeclear.probe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.gaon.codeclear.translation.MlKitLocalEngine
import xyz.gaon.codeclear.translation.TranslationCoordinator
import xyz.gaon.codeclear.translation.TranslationInput
import xyz.gaon.codeclear.translation.TranslationState
import xyz.gaon.codeclear.translation.TranslationTarget

/** Process-scoped preview state, separate from the future persistent translation repository. */
object ProbeLocalTranslation {
    var enabled by mutableStateOf(false)
        private set
    var target by mutableStateOf(TranslationTarget.ENGLISH)
        private set
    var input by mutableStateOf<TranslationInput?>(null)
        private set
    var state by mutableStateOf<TranslationState>(TranslationState.Idle)
        private set
    private val coordinator by lazy {
        TranslationCoordinator(MlKitLocalEngine()) { state = it }
    }

    fun enable(value: Boolean) {
        enabled = value
        if (!value) select(null)
    }

    fun chooseTarget(value: TranslationTarget) {
        if (target == value) return
        target = value
        select(null)
    }

    fun select(value: TranslationInput?) {
        input = value
        coordinator.select(value)
    }

    fun retry(expected: TranslationInput, download: Boolean = false) {
        if (input == expected && enabled) coordinator.retry(download)
    }
}
