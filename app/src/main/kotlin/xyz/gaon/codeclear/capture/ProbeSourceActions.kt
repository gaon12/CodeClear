package xyz.gaon.codeclear.capture

import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Keeps one source capability in memory. It is sent only by an explicit foreground UI action. */
object ProbeSourceActions {
    private data class SourceAction(
        val generation: Long,
        val key: String,
        val packageName: String,
        val intent: PendingIntent
    )

    private const val MESSAGE_EXTRA = "com.android.cellbroadcastreceiver.SMS_CB_MESSAGE"
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private var offered: SourceAction? = null
    private var operation = 0L
    var state by mutableStateOf(ProbeSourceActionState())
        private set

    fun offer(generation: Long, key: String, packageName: String, intent: PendingIntent?) {
        if (intent == null || intent.creatorPackage != packageName ||
            intent.creatorUserHandle != Process.myUserHandle()
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !intent.isActivity) return
        offered = SourceAction(generation, key, packageName, intent)
        state = state.copy(available = true)
    }

    fun removed(key: String) {
        if (offered?.key == key) {
            offered = null
            state = state.copy(available = false)
        }
    }

    fun clear() {
        offered = null
        operation++
        main.removeCallbacksAndMessages(null)
        state = ProbeSourceActionState()
    }

    @Suppress("DEPRECATION")
    fun openFromUser(activity: ComponentActivity) {
        if (state.busy ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            return
        }
        val action = offered ?: return
        val environment = ProbeEnvironment.read(activity)
        val ticket = ProbeSession.ticket(ProbeRoute.NOTIFICATION, environment)
        if (ticket != action.generation || action.packageName !in environment.systemPackages) {
            clear()
            return
        }
        val attempt = ++operation
        offered = null
        state = ProbeSourceActionState(busy = true)
        fun finish(result: ProbeSourceActionState) {
            if (operation == attempt && ProbeSession.currentGeneration() == ticket) {
                operation++
                state = result.copy(available = offered != null)
                main.removeCallbacksAndMessages(null)
            }
        }
        main.postDelayed({
            finish(ProbeSourceActionState(failure = ProbeSourceActionFailure.TIMEOUT))
        }, 5000)
        try {
            action.intent.send(
                activity,
                0,
                null,
                { _, original, _, _, _ ->
                    if (operation != attempt ||
                        ProbeSession.currentGeneration() != ticket
                    ) {
                        return@send
                    }
                    val component = original.component
                    if (component?.packageName != action.packageName ||
                        component.className != ProbeWindowTracker.ALERT_CLASS
                    ) {
                        finish(
                            ProbeSourceActionState(failure = ProbeSourceActionFailure.UNSUPPORTED)
                        )
                        return@send
                    }
                    val observedBodies = ProbeSession.state.records.mapNotNull {
                        it.observation.takeIf { observation ->
                            observation.packageName == action.packageName &&
                                observation.bodyEvidence != ProbeBodyEvidence.UNVERIFIED &&
                                observation.body.isNotBlank()
                        }?.body
                    }
                    try {
                        worker.execute {
                            var decoded = emptyList<ProbeDecodedBroadcast>()
                            val result = try {
                                // Use the public Intent/Parcelable APIs; never call hidden message getters.
                                val messages = original.getParcelableArrayListExtra<Parcelable>(
                                    MESSAGE_EXTRA
                                )
                                if (messages == null) {
                                    ProbeSourceActionState(
                                        failure = ProbeSourceActionFailure.UNSUPPORTED
                                    )
                                } else if (messages.size > 40) {
                                    ProbeSourceActionState(
                                        failure = ProbeSourceActionFailure.LIMITED
                                    )
                                } else {
                                    val types =
                                        inspectSourcePayloadTypes(
                                            messages.map {
                                                it.javaClass.name
                                            }
                                        )
                                    if (types.recognizedTypes) {
                                        val batch = messages.map {
                                            ProbeBroadcastPayload.decode(it)
                                        }
                                        if (batch.all { it != null }) {
                                            decoded = batch.filterNotNull()
                                            types.copy(
                                                importedMessages = decoded.size,
                                                matchingObservedBodies = decoded.count {
                                                    it.body in observedBodies
                                                }
                                            )
                                        } else {
                                            types.copy(
                                                failure = ProbeSourceActionFailure.UNSUPPORTED
                                            )
                                        }
                                    } else {
                                        types.copy(failure = ProbeSourceActionFailure.UNSUPPORTED)
                                    }
                                }
                            } catch (_: RuntimeException) {
                                ProbeSourceActionState(
                                    failure = ProbeSourceActionFailure.UNSUPPORTED
                                )
                            }
                            main.post {
                                if (operation == attempt &&
                                    ProbeSession.currentGeneration() == ticket &&
                                    activity.isUnlocked() &&
                                    ProbeSession.ticket(
                                        ProbeRoute.NOTIFICATION,
                                        ProbeEnvironment.read(activity)
                                    ) ==
                                    ticket
                                ) {
                                    decoded.forEach { message ->
                                        ProbeSession.publish(
                                            ticket,
                                            ProbeObservation(
                                                packageName = action.packageName,
                                                route = ProbeRoute.NOTIFICATION,
                                                origin = ProbeOrigin.SYSTEM_CANDIDATE,
                                                title = "",
                                                body = message.body,
                                                capturedAtMillis = System.currentTimeMillis(),
                                                bodyEvidence =
                                                    ProbeBodyEvidence.SOURCE_NOTIFICATION_PAYLOAD,
                                                broadcastFields = message.fields
                                            )
                                        )
                                    }
                                    finish(result)
                                }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        finish(
                            ProbeSourceActionState(failure = ProbeSourceActionFailure.UNAVAILABLE)
                        )
                    }
                },
                main
            )
        } catch (_: PendingIntent.CanceledException) {
            finish(ProbeSourceActionState(failure = ProbeSourceActionFailure.UNAVAILABLE))
        } catch (_: SecurityException) {
            finish(ProbeSourceActionState(failure = ProbeSourceActionFailure.UNAVAILABLE))
        }
    }
}
