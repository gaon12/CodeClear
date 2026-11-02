package xyz.gaon.codeclear.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import xyz.gaon.codeclear.MainActivity
import xyz.gaon.codeclear.R

internal interface DeferredAlertSurface {
    val foreground: Boolean
    fun show(candidate: DeferredAlert, options: MobileOptions, dismissed: () -> Unit)
    fun hide()
}

/** A small secure window with no wake, unlock, full-screen, or input-focus privilege. */
internal class DeferredAlertWindow(private val strings: () -> Context) : DeferredAlertSurface {
    private var activity = WeakReference<Activity>(null)
    private var focused = false
    private var dialog: AlertDialog? = null
    private var shown: DeferredAlert? = null
    private var body: TextView? = null
    private var heading: TextView? = null
    private var translated = false
    private var overlayContext: Context? = null
    override val foreground: Boolean get() = activity.get() != null

    fun started(value: Activity) {
        activity = WeakReference(value)
        focused = false
    }

    fun focus(value: Activity, hasFocus: Boolean) {
        if (activity.get() === value) focused = hasFocus
    }

    fun stopped(value: Activity) {
        if (activity.get() === value) {
            activity.clear()
            focused = false
        }
    }

    override fun show(candidate: DeferredAlert, options: MobileOptions, dismissed: () -> Unit) {
        val owner = activity.get()
        val resources = strings()
        if (owner != null && (
                (!focused && dialog?.isShowing != true) ||
                    owner.isFinishing || owner.isDestroyed
                )
        ) {
            return hide()
        }
        if (owner == null && !Settings.canDrawOverlays(resources)) return hide()
        if (shown?.id == candidate.id && dialog?.isShowing == true) {
            shown = candidate
            updateText(resources)
            return
        }
        hide()
        val windowContext = if (owner != null) {
            owner
        } else {
            overlayContext ?: run {
                val display = resources.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY) ?: return
                resources.applicationContext.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    .also { overlayContext = it }
            }
        }
        val dark = options.theme == ThemeChoice.DARK ||
            (
                options.theme == ThemeChoice.SYSTEM &&
                    resources.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                )
        val themed = ContextThemeWrapper(
            windowContext,
            if (dark) {
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            } else {
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            }
        )
        val padding = (24 * themed.resources.displayMetrics.density).toInt()
        val primaryText = if (dark) Color.rgb(241, 241, 241) else Color.rgb(26, 26, 26)
        val secondaryText = if (dark) Color.rgb(198, 198, 198) else Color.rgb(98, 98, 98)
        val column = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val label = TextView(themed).apply {
            setTextColor(secondaryText)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            setPadding(0, 0, 0, padding / 2)
        }
        val content = TextView(themed).apply {
            setTextColor(primaryText)
            textSize = 18f
        }
        column.addView(label)
        column.addView(content)
        column.addView(
            TextView(themed).apply {
                setTextColor(secondaryText)
                text = resources.getString(R.string.mobile_translation_short_notice)
                textSize = 12f
                setPadding(0, padding, 0, 0)
            }
        )
        val metrics = windowContext.getSystemService(WindowManager::class.java)
            .currentWindowMetrics.bounds
        val maximumBodyHeight = minOf(
            (metrics.height() * 0.5f).toInt(),
            (440 * themed.resources.displayMetrics.density).toInt()
        )
        val scroll = object : ScrollView(themed) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    maximumBodyHeight
                } else {
                    minOf(maximumBodyHeight, MeasureSpec.getSize(heightMeasureSpec))
                }
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
                )
            }
        }.apply { addView(column) }
        val next = AlertDialog.Builder(themed)
            .setTitle(resources.getString(R.string.app_name))
            .setView(scroll)
            .setNegativeButton(resources.getString(R.string.mobile_close)) { _, _ -> }
            .setPositiveButton(resources.getString(R.string.mobile_details)) { clicked, _ ->
                // Consume before navigation can replace the activity and its window callbacks.
                if (dialog === clicked) {
                    hide()
                    dismissed()
                }
                try {
                    windowContext.startActivity(
                        Intent(windowContext, MainActivity::class.java).apply {
                            data = ResultNotifications.destinationUri(
                                candidate.id,
                                candidate.target,
                                candidate.engine
                            )
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        }
                    )
                } catch (_: RuntimeException) {
                    // History and the existing notification remain available.
                }
            }
            .setNeutralButton(resources.getString(R.string.mobile_original)) { _, _ -> }
            .create()
        next.setCanceledOnTouchOutside(false)
        next.window?.apply {
            if (owner == null) setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            addFlags(
                WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            if (owner == null) addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { windowAnimations = 0 }
        }
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                shown = null
                body = null
                heading = null
                dismissed()
            }
        }
        try {
            next.show()
            val width = minOf(
                metrics.width() - padding,
                (
                    480 *
                        themed.resources.displayMetrics.density
                    ).toInt()
            )
            next.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            dialog = next
            shown = candidate
            body = content
            heading = label
            translated = false
            next.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                translated = !translated
                updateText(resources)
            }
            updateText(resources)
        } catch (failure: RuntimeException) {
            next.setOnDismissListener(null)
            runCatching { next.dismiss() }
            hide()
            throw failure
        }
    }

    private fun updateText(context: Context) {
        val current = shown ?: return
        val hasTranslation = current.translation != null
        body?.text =
            if (translated && hasTranslation) current.translation else current.capture.original
        heading?.text = context.getString(
            if (translated &&
                hasTranslation
            ) {
                R.string.mobile_translation
            } else {
                R.string.mobile_original
            },
            languageName(current.target)
        )
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            isEnabled = hasTranslation
            text = if (translated) {
                context.getString(R.string.mobile_original)
            } else {
                context.getString(R.string.mobile_translation, languageName(current.target))
            }
        }
    }

    override fun hide() {
        val previous = dialog
        dialog = null
        shown = null
        body = null
        heading = null
        previous?.setOnDismissListener(null)
        runCatching { previous?.dismiss() }
    }
}
