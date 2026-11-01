package xyz.gaon.codeclear.probe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Process-scoped preview state, separate from the future persistent translation repository. */
object ProbeLocalTranslation {
    var enabled by mutableStateOf(false)
        private set
    var target by mutableStateOf(LocalTarget.ENGLISH)
        private set
    var input by mutableStateOf<LocalInput?>(null)
        private set
    var state by mutableStateOf<LocalState>(LocalState.Idle)
        private set
    private val coordinator by lazy {
        LocalTranslationCoordinator(MlKitLocalEngine()) { state = it }
    }

    fun enable(value: Boolean) {
        enabled = value
        if (!value) select(null)
    }

    fun chooseTarget(value: LocalTarget) {
        if (target == value) return
        target = value
        select(null)
    }

    fun select(value: LocalInput?) {
        input = value
        coordinator.select(value)
    }

    fun retry(expected: LocalInput, download: Boolean = false) {
        if (input == expected && enabled) coordinator.retry(download)
    }
}
