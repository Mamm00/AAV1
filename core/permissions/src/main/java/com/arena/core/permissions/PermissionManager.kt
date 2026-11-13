package com.arena.core.permissions

import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted-per-session bookkeeping so policy decisions survive recreation. */
interface PermissionStateStore {
    fun hasRequested(useCase: PermissionUseCase): Boolean
    fun denialCount(useCase: PermissionUseCase): Int
    fun markRequested(useCase: PermissionUseCase)
    fun recordResult(useCase: PermissionUseCase, granted: Boolean)
}

/** Default store: in-memory, private to the process, never leaves the device. */
class InMemoryPermissionStateStore : PermissionStateStore {

    private val requested = mutableSetOf<PermissionUseCase>()
    private val denials = mutableMapOf<PermissionUseCase, Int>()

    override fun hasRequested(useCase: PermissionUseCase): Boolean = useCase in requested

    override fun denialCount(useCase: PermissionUseCase): Int = denials[useCase] ?: 0

    override fun markRequested(useCase: PermissionUseCase) {
        requested += useCase
    }

    override fun recordResult(useCase: PermissionUseCase, granted: Boolean) {
        if (!granted) denials[useCase] = denialCount(useCase) + 1
    }
}

/** Side-effect the UI must fulfil after a decision (e.g. open settings). */
sealed interface PermissionEffect {
    /** The pre-permission rationale sheet must be shown for [useCase]. */
    data class ShowRationale(val useCase: PermissionUseCase) : PermissionEffect

    /** Fire the system permission dialog for [permissions]. */
    data class LaunchSystemDialog(val useCase: PermissionUseCase, val permissions: List<String>) : PermissionEffect

    /** The action can proceed (permission already held). */
    data object Proceed : PermissionEffect

    /**
     * Permission is blocked; open the documented fallback path.
     * The property type is fully qualified because the simple name
     * [Fallback] inside this scope would resolve to this class itself.
     */
    data class Fallback(
        val useCase: PermissionUseCase,
        val fallback: com.arena.core.permissions.Fallback,
    ) : PermissionEffect
}

/**
 * Orchestrates contextual permission requests:
 *
 * ```
 * user taps action ──► evaluate() ──► ShowRationale sheet
 *                          │                │ Continue
 *                          │                ▼
 *                          │         LaunchSystemDialog
 *                          │                │ result
 *                          ▼                ▼
 *                    Proceed / Fallback (recorded in DenialStats)
 * ```
 *
 * Never called at launch. Every entry point here is a deliberate user tap
 * (take photo, mic, browse library, enable notifications).
 */
class PermissionManager(
    private val policy: PermissionPolicy = PermissionPolicy(),
    private val store: PermissionStateStore = InMemoryPermissionStateStore(),
    val stats: DenialStats = DenialStats(),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    private val _effects = MutableSharedFlow<PermissionEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<PermissionEffect> = _effects.asSharedFlow()

    private val _pendingRationale = MutableStateFlow<PermissionUseCase?>(null)

    /** Non-null while the rationale sheet should be visible. */
    val pendingRationale: StateFlow<PermissionUseCase?> = _pendingRationale.asStateFlow()

    /** Permissions to request for [useCase], accounting for API level. */
    fun requiredPermissions(useCase: PermissionUseCase): List<String> = when (useCase) {
        PermissionUseCase.CAMERA,
        PermissionUseCase.RECORD_AUDIO,
        PermissionUseCase.POST_NOTIFICATIONS,
        -> listOf(useCase.permission)

        PermissionUseCase.READ_MEDIA_IMAGES ->
            if (sdkInt >= 33) PermissionUseCase.mediaPermissions()
            else listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Notification permission pre-dates 33: always granted, never ask. */
    private fun isImplicitlyGranted(useCase: PermissionUseCase): Boolean =
        useCase == PermissionUseCase.POST_NOTIFICATIONS && sdkInt < 33

    /**
     * Entry point: the user just tapped an action that needs [useCase].
     * @return true if the action may proceed immediately.
     */
    fun evaluate(
        useCase: PermissionUseCase,
        hasPermission: Boolean,
        canShowRationale: Boolean,
    ): Boolean {
        val granted = hasPermission || isImplicitlyGranted(useCase)
        val decision = policy.decide(
            granted = granted,
            requestedBefore = store.hasRequested(useCase),
            canShowRationale = canShowRationale,
            denialCount = store.denialCount(useCase),
            fallback = fallbackFor(useCase),
        )
        return when (decision) {
            PermissionDecision.AlreadyGranted -> {
                _effects.trySend(PermissionEffect.Proceed)
                true
            }
            PermissionDecision.ShowRationale -> {
                _pendingRationale.value = useCase
                _effects.trySend(PermissionEffect.ShowRationale(useCase))
                false
            }
            is PermissionDecision.UseFallback -> {
                _effects.trySend(PermissionEffect.Fallback(useCase, decision.fallback))
                false
            }
            PermissionDecision.RequestSystemDialog -> {
                launchSystemDialog(useCase)
                false
            }
        }
    }

    /** User tapped Continue on the rationale sheet. */
    fun onRationaleContinued(useCase: PermissionUseCase, canShowRationale: Boolean) {
        _pendingRationale.value = null
        val decision = policy.afterRationaleAccepted(
            granted = false,
            canShowRationale = canShowRationale,
            fallback = fallbackFor(useCase),
        )
        when (decision) {
            PermissionDecision.RequestSystemDialog -> launchSystemDialog(useCase)
            is PermissionDecision.UseFallback ->
                _effects.trySend(PermissionEffect.Fallback(useCase, decision.fallback))
            else -> Unit
        }
    }

    /** User dismissed the rationale sheet. */
    fun onRationaleDismissed() {
        _pendingRationale.value = null
    }

    /**
     * System dialog result. [canShowRationale] is the value of
     * `shouldShowRequestPermissionRationale` AFTER the result — false here
     * after a denial means "don't ask again" (permanently blocked).
     */
    fun onSystemDialogResult(
        useCase: PermissionUseCase,
        granted: Boolean,
        canShowRationale: Boolean,
    ) {
        store.markRequested(useCase)
        store.recordResult(useCase, granted)
        stats.recordRequest()
        stats.recordResult(granted)

        if (granted) {
            _effects.trySend(PermissionEffect.Proceed)
            return
        }
        val blocked = store.hasRequested(useCase) && !canShowRationale && store.denialCount(useCase) > 0
        if (blocked) {
            _effects.trySend(PermissionEffect.Fallback(useCase, fallbackFor(useCase)))
        }
        // Soft denial: stay silent; the next tap re-evaluates the flow.
    }

    private fun launchSystemDialog(useCase: PermissionUseCase) {
        store.markRequested(useCase)
        _effects.trySend(
            PermissionEffect.LaunchSystemDialog(useCase, requiredPermissions(useCase)),
        )
    }

    private fun fallbackFor(useCase: PermissionUseCase): Fallback = when (useCase) {
        PermissionUseCase.CAMERA -> Fallback.PHOTO_PICKER
        PermissionUseCase.RECORD_AUDIO -> Fallback.KEYBOARD_INPUT
        PermissionUseCase.READ_MEDIA_IMAGES -> Fallback.STORAGE_ACCESS_FRAMEWORK
        PermissionUseCase.POST_NOTIFICATIONS -> Fallback.IN_APP_TASK_CENTER
    }
}
