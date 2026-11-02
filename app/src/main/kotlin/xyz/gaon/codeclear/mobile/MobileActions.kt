package xyz.gaon.codeclear.mobile

import xyz.gaon.codeclear.nearby.NearbyState
import xyz.gaon.codeclear.translation.SystemEngineAvailability
import xyz.gaon.codeclear.translation.TranslationEngineKind

class MobileActions(
    val configure: (MobileOptions) -> Unit,
    val search: (String) -> Unit,
    val detail: (Long?) -> Unit,
    val delete: (Long?) -> Unit,
    val retry: (Long, String, TranslationEngineKind) -> Unit,
    val prepare: (SavedAlert, String, TranslationEngineKind) -> Unit,
    val settings: (String) -> Unit,
    val openSource: () -> Unit,
    val notificationPermission: () -> Unit,
    val notificationsAllowed: Boolean,
    val older: () -> Unit,
    val newest: () -> Unit,
    val retryStorage: () -> Unit,
    val systemAvailability: SystemEngineAvailability = SystemEngineAvailability(),
    val refreshSystem: (Boolean) -> Unit = {},
    val systemSettings: () -> Unit = {},
    val changeDetailTarget: (Long, String, String) -> Unit = { _, _, _ -> },
    val identifyOriginal: (String, (String?) -> Unit) -> AutoCloseable = { _, complete ->
        complete(null)
        AutoCloseable {}
    },
    val nearby: NearbyState = NearbyState(),
    val nearbyPermission: () -> Unit = {},
    val refreshNearby: () -> Unit = {},
    val dismissNearby: (String) -> Unit = {},
    val saveNearby: (String, (Long?) -> Unit) -> Unit = { _, complete -> complete(null) },
    val speechState: SpeechState = SpeechState(),
    val readAloud: (String, String, String) -> Unit = { _, _, _ -> },
    val stopReading: () -> Unit = {},
    val modelInventory: ModelInventoryState = ModelInventoryState(),
    val modelPreparation: PreparationState = PreparationState(),
    val refreshModels: (Boolean) -> Unit = {},
    val prepareModel: (String) -> Unit = {},
    val modelDownload: ModelDownloadRequest? = null,
    val prepareTargetModels: (
        xyz.gaon.codeclear.translation.TranslationTarget,
        SavedAlert?
    ) -> Unit = { _, _ -> },
    val startModelDownload: () -> Unit = {},
    val closeModelDownload: () -> Unit = {},
    val showModelDownload: () -> Unit = {},
    val copyBody: (String) -> Unit = {},
    val shareBody: (String) -> Unit = {},
    val presentationAccess: PresentationAccess = PresentationAccess(),
    val overlaySettings: () -> Unit = {},
    val batterySettings: () -> Unit = {},
    val olderObservations: () -> Unit = {},
    val newestObservations: () -> Unit = {}
)
