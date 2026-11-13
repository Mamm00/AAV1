package com.arena.core.permissions

/**
 * Why a permission is being requested. Each use case maps to a contextual
 * trigger — never a blanket launch-time request.
 */
enum class PermissionUseCase(val permission: String) {
    /** User tapped "Take photo" in the image editor or agent attachments. */
    CAMERA(android.Manifest.permission.CAMERA),

    /** User pressed/hold the mic for voice input or an agent voice note. */
    RECORD_AUDIO(android.Manifest.permission.RECORD_AUDIO),

    /** User chose "browse full library" (Photo Picker needs no permission). */
    READ_MEDIA_IMAGES(android.Manifest.permission.READ_MEDIA_IMAGES),

    /** Long-running agent/battle notifications were enabled by the user. */
    POST_NOTIFICATIONS(android.Manifest.permission.POST_NOTIFICATIONS);

    companion object {
        /** Permissions for [android.os.Build.VERSION_CODES.TIRAMISU]+. */
        fun mediaPermissions(): List<String> = listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO,
        )
    }
}

/** What the UI should do next for a given permission state. */
sealed interface PermissionDecision {
    /** Show the Compose pre-permission rationale sheet first. */
    data object ShowRationale : PermissionDecision

    /** Rationale already accepted (or previously shown): fire the system dialog. */
    data object RequestSystemDialog : PermissionDecision

    /** Already granted — proceed with the action. */
    data object AlreadyGranted : PermissionDecision

    /** Permanently denied: don't nag, use the documented fallback instead. */
    data class UseFallback(val fallback: Fallback) : PermissionDecision
}

/** Denial fallbacks keep every feature usable without the permission. */
enum class Fallback {
    /** Camera denied → Android Photo Picker (no permission required). */
    PHOTO_PICKER,

    /** Mic denied → keyboard-only prompt entry. */
    KEYBOARD_INPUT,

    /** Media denied → Photo Picker / Storage Access Framework. */
    STORAGE_ACCESS_FRAMEWORK,

    /** Notifications denied → in-app task center only. */
    IN_APP_TASK_CENTER,
}

/**
 * Pure decision logic for contextual permission requests.
 *
 * Rule set (mirrors the product spec):
 *  1. Granted → [PermissionDecision.AlreadyGranted].
 *  2. Never asked → [PermissionDecision.ShowRationale] (pre-permission sheet).
 *  3. Asked, soft-denied, rationale still showable → ShowRationale again.
 *  4. Asked, system says rationale must NOT be shown (i.e. "don't ask again"
 *     or denied twice on older Androids) → [PermissionDecision.UseFallback].
 *
 * The policy itself never touches Android framework APIs so it can be unit
 * tested on the JVM; [PermissionManager] supplies the framework signals.
 */
class PermissionPolicy {

    fun decide(
        granted: Boolean,
        requestedBefore: Boolean,
        canShowRationale: Boolean,
        denialCount: Int,
        fallback: Fallback,
    ): PermissionDecision = when {
        granted -> PermissionDecision.AlreadyGranted
        // Permanent block: the system will silently deny the dialog.
        requestedBefore && !canShowRationale && denialCount > 0 ->
            PermissionDecision.UseFallback(fallback)
        // First ask, or a soft denial where rationale may still be shown.
        else -> PermissionDecision.ShowRationale
    }

    /**
     * After the user taps Continue on the rationale sheet, the system dialog
     * is shown directly (no second rationale).
     */
    fun afterRationaleAccepted(
        granted: Boolean,
        canShowRationale: Boolean,
        fallback: Fallback,
    ): PermissionDecision = when {
        granted -> PermissionDecision.AlreadyGranted
        canShowRationale -> PermissionDecision.RequestSystemDialog
        else -> PermissionDecision.UseFallback(fallback)
    }
}

/**
 * Rolling denial-rate statistics used to refine the pre-permission flow.
 * Kept local and private — never leaves the device.
 */
class DenialStats {

    private var requests = 0
    private var denials = 0

    val denialRate: Double
        get() = if (requests == 0) 0.0 else denials.toDouble() / requests

    val requestCount: Int get() = requests
    val denialCount: Int get() = denials

    fun recordRequest() { requests++ }

    fun recordResult(granted: Boolean) {
        if (!granted) denials++
    }

    fun snapshot(): Snapshot = Snapshot(requests, denials)

    data class Snapshot(val requests: Int, val denials: Int) {
        val rate: Double get() = if (requests == 0) 0.0 else denials.toDouble() / requests
    }
}
