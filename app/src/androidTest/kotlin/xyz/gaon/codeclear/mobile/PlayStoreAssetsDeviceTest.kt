package xyz.gaon.codeclear.mobile

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeSessionState
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

/** Explicit synthetic asset export; excluded from automatic fixtures and release packages. */
class PlayStoreAssetsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MobileFixtureActivity>()
    private var language by mutableStateOf("en")
    private var state by mutableStateOf(MobileState())
    private val context get() = compose.activity

    @Test
    fun exportLocalizedSyntheticScreensAndNativeBrandAssets() {
        assumeTrue(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName ==
                "xyz.gaon.codeclear.validation"
        )
        compose.runOnUiThread {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            context.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            state = fixtureState("en")
        }
        compose.setContent {
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = remember(base, configuration, language) {
                base.createConfigurationContext(
                    Configuration(configuration).apply {
                        setLocale(Locale.forLanguageTag(language))
                    }
                )
            }
            LaunchedEffect(state.options.theme) {
                val transparent = Color.TRANSPARENT
                val dark = state.options.theme == ThemeChoice.DARK
                context.enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.BLACK)
                    } else {
                        SystemBarStyle.light(Color.WHITE, Color.WHITE)
                    }
                )
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalConfiguration provides localized.resources.configuration
            ) {
                key(language) {
                    MobileScreen(
                        state,
                        ProbeEnvironment(
                            ProbeRadio.PRESENT,
                            setOf("test.system.receiver"),
                            true,
                            true
                        ),
                        ProbeSessionState(
                            notificationConnected = true,
                            accessibilityConnected = true
                        ),
                        false,
                        PreparationState(),
                        fixtureActions()
                    )
                }
            }
        }
        for (tag in listOf("en", "ko", "ja", "zh-Hans", "zh-Hant", "fr", "de")) {
            compose.runOnIdle {
                language = tag
                state = fixtureState(tag)
            }
            compose.onNodeWithTag("destination-0").assertIsDisplayed()
            saveScreen("$tag/01-home.jpg")
            compose.onNodeWithTag("destination-1").performClick()
            compose.onNodeWithTag("history-row-1").assertIsDisplayed()
            saveScreen("$tag/02-history.jpg")
            compose.onNodeWithTag("history-row-1").performClick()
            compose.onNodeWithTag("history-detail").assertIsDisplayed()
            saveScreen("$tag/03-detail.jpg")
            compose.onNodeWithTag("history-detail-back").performClick()
            compose.onNodeWithTag("destination-0").performClick()
            compose.runOnIdle {
                state =
                    state.copy(options = state.options.copy(theme = ThemeChoice.DARK))
            }
            saveScreen("$tag/04-dark.jpg")
        }
        compose.runOnIdle { saveBrandAssets() }
    }

    private fun fixtureActions() = MobileActions(
        configure = { state = state.copy(options = it) },
        search = {},
        detail = { id ->
            state = state.copy(
                detailId = id,
                detailTarget = state.options.target.languageTag,
                detailEngine = TranslationEngineKind.ML_KIT,
                detailSourceLanguage = "ko",
                detail = state.records.firstOrNull { it.id == id }
            )
        },
        delete = { error("Asset fixtures cannot delete history") },
        retry = { _, _, _ -> error("Asset fixtures cannot send translation requests") },
        prepare = { _, _, _ -> error("Asset fixtures cannot download models") },
        settings = {},
        openSource = {},
        notificationPermission = {},
        notificationsAllowed = true,
        older = {},
        newest = {},
        retryStorage = {},
        identifyOriginal = { _, done ->
            done("ko")
            AutoCloseable {}
        },
        modelInventory = ModelInventoryState(loaded = true, downloaded = primaryModelLanguages)
    )

    private fun fixtureState(tag: String): MobileState {
        val target = TranslationTarget.entries.first {
            it.languageTag ==
                if (tag == "ko") "en" else tag
        }
        val originals = listOf(
            "[훈련] 강풍에 주의하고 해안 접근을 피하세요. [예시안전센터]",
            "[훈련] 하천 수위가 높습니다. 하천 주변 접근을 피하세요. [예시안전센터]",
            "[훈련] 예시 도로가 통제됩니다. 우회 도로를 이용하세요. [예시안전센터]"
        )
        val translated = when (tag) {
            "ja" -> listOf(
                "[訓練] 強風に注意し、海岸に近づかないでください。[例の安全センター]",
                "[訓練] 川が増水しています。川に近づかないでください。[例の安全センター]",
                "[訓練] 例の道路は通行止めです。迂回路を利用してください。[例の安全センター]"
            )

            "zh-Hans" -> listOf(
                "[演练] 请注意强风，避免靠近海岸。[示例安全中心]",
                "[演练] 河流水位较高，请勿靠近河流。[示例安全中心]",
                "[演练] 示例道路已封闭，请绕行。[示例安全中心]"
            )

            "zh-Hant" -> listOf(
                "[演練] 請注意強風，避免靠近海岸。[範例安全中心]",
                "[演練] 河流水位較高，請勿靠近河流。[範例安全中心]",
                "[演練] 範例道路已封閉，請改道。[範例安全中心]"
            )

            "fr" -> listOf(
                "[EXERCICE] Vents forts : évitez la côte. [Centre fictif]",
                "[EXERCICE] La rivière est en crue. Restez à distance. [Centre fictif]",
                "[EXERCICE] La route fictive est fermée. Suivez la déviation. [Centre fictif]"
            )

            "de" -> listOf(
                "[ÜBUNG] Starker Wind. Meiden Sie die Küste. [Beispielzentrum]",
                "[ÜBUNG] Der Fluss führt Hochwasser. Halten Sie Abstand. [Beispielzentrum]",
                "[ÜBUNG] Die Beispielstraße ist gesperrt. Nutzen Sie die Umleitung. [Beispielzentrum]"
            )

            else -> listOf(
                "[DRILL] Strong winds: stay away from the coast. [Example Safety Center]",
                "[DRILL] The river is high. Stay away from the river. [Example Safety Center]",
                "[DRILL] The example road is closed. Use the detour. [Example Safety Center]"
            )
        }
        val records = (1L..3L).map { id ->
            SavedAlert(
                id,
                CapturedAlert(
                    "test.system.receiver",
                    CaptureSource.NOTIFICATION,
                    "[훈련] 예시 안전 안내",
                    originals[(id - 1).toInt()],
                    1_793_613_600_000L - (id - 1) * 86_400_000L
                ),
                SavedTranslation(TranslationPhase.READY, translated[(id - 1).toInt()], "ko")
            )
        }
        return MobileState(
            loaded = true,
            options = MobileOptions(
                notificationConsent = true,
                accessibilityConsent = true,
                target = target,
                localConsent = true,
                setupComplete = true,
                uiLanguage = tag,
                theme = ThemeChoice.LIGHT,
                messageViewMode = MessageViewMode.BOTH
            ),
            records = records,
            latest = records.first()
        )
    }

    private fun saveScreen(name: String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val complete = CountDownLatch(1)
        val result = AtomicInteger(-1)
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val decor = context.window.decorView
            assertEquals(1080, decor.width)
            assertEquals(1920, decor.height)
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(context.window, bitmap, {
                result.set(it)
                complete.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        try {
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result.get())
            writeBitmap(name, bitmap, Bitmap.CompressFormat.JPEG)
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveBrandAssets() {
        val icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val graphic = Bitmap.createBitmap(1024, 500, Bitmap.Config.ARGB_8888)
        try {
            val iconCanvas = Canvas(icon)
            iconCanvas.drawColor(Color.rgb(25, 50, 59))
            requireNotNull(
                ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
            ).apply {
                setBounds(-128, -128, 640, 640)
                draw(iconCanvas)
            }
            writeBitmap("icon.png", icon, Bitmap.CompressFormat.PNG)
            val canvas = Canvas(graphic)
            canvas.drawColor(Color.rgb(25, 50, 59))
            val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(icon, null, android.graphics.Rect(64, 100, 364, 400), bitmapPaint)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(245, 242, 233)
                textSize = 84f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            canvas.drawText("CodeClear", 412f, 282f, paint)
            writeBitmap("feature.jpg", graphic, Bitmap.CompressFormat.JPEG)
        } finally {
            icon.recycle()
            graphic.recycle()
        }
    }

    private fun writeBitmap(name: String, bitmap: Bitmap, format: Bitmap.CompressFormat) {
        val file = File(context.cacheDir, "play-assets/$name")
        check(requireNotNull(file.parentFile).let { it.isDirectory || it.mkdirs() })
        file.outputStream().use { assertTrue(bitmap.compress(format, 95, it)) }
    }
}
