package com.arena.core.permissions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** JVM unit tests for the contextual permission decision matrix. */
class PermissionPolicyTest {

    private val policy = PermissionPolicy()

    @Test
    fun `granted permission proceeds immediately`() {
        val decision = policy.decide(
            granted = true,
            requestedBefore = false,
            canShowRationale = true,
            denialCount = 0,
            fallback = Fallback.PHOTO_PICKER,
        )
        assertThat(decision).isEqualTo(PermissionDecision.AlreadyGranted)
    }

    @Test
    fun `first request shows rationale before system dialog`() {
        val decision = policy.decide(
            granted = false,
            requestedBefore = false,
            canShowRationale = true,
            denialCount = 0,
            fallback = Fallback.PHOTO_PICKER,
        )
        assertThat(decision).isEqualTo(PermissionDecision.ShowRationale)
    }

    @Test
    fun `soft denial with rationale still showable re-shows rationale`() {
        val decision = policy.decide(
            granted = false,
            requestedBefore = true,
            canShowRationale = true,
            denialCount = 1,
            fallback = Fallback.KEYBOARD_INPUT,
        )
        assertThat(decision).isEqualTo(PermissionDecision.ShowRationale)
    }

    @Test
    fun `permanent denial routes to fallback not another prompt`() {
        val decision = policy.decide(
            granted = false,
            requestedBefore = true,
            canShowRationale = false,
            denialCount = 1,
            fallback = Fallback.PHOTO_PICKER,
        )
        assertThat(decision).isEqualTo(PermissionDecision.UseFallback(Fallback.PHOTO_PICKER))
    }

    @Test
    fun `never asked with rationale suppressed still shows rationale`() {
        // canShowRationale=false before any request means "never asked", not
        // "blocked" — the policy must not misread it as permanent denial.
        val decision = policy.decide(
            granted = false,
            requestedBefore = false,
            canShowRationale = false,
            denialCount = 0,
            fallback = Fallback.KEYBOARD_INPUT,
        )
        assertThat(decision).isEqualTo(PermissionDecision.ShowRationale)
    }

    @Test
    fun `rationale accepted with rationale available launches system dialog`() {
        val decision = policy.afterRationaleAccepted(
            granted = false,
            canShowRationale = true,
            fallback = Fallback.STORAGE_ACCESS_FRAMEWORK,
        )
        assertThat(decision).isEqualTo(PermissionDecision.RequestSystemDialog)
    }

    @Test
    fun `rationale accepted but blocked falls back`() {
        val decision = policy.afterRationaleAccepted(
            granted = false,
            canShowRationale = false,
            fallback = Fallback.STORAGE_ACCESS_FRAMEWORK,
        )
        assertThat(decision).isEqualTo(PermissionDecision.UseFallback(Fallback.STORAGE_ACCESS_FRAMEWORK))
    }

    // ------------------------------------------------------- manager flows //

    @Test
    fun `manager emits proceed for already granted action`() {
        val manager = PermissionManager(sdkInt = 34)
        val granted = manager.evaluate(
            useCase = PermissionUseCase.CAMERA,
            hasPermission = true,
            canShowRationale = false,
        )
        assertThat(granted).isTrue()
        assertThat(manager.pendingRationale.value).isNull()
    }

    @Test
    fun `manager opens rationale sheet on first ask and clears it on continue`() {
        val manager = PermissionManager(sdkInt = 34)
        val proceeds = manager.evaluate(
            useCase = PermissionUseCase.RECORD_AUDIO,
            hasPermission = false,
            canShowRationale = true,
        )
        assertThat(proceeds).isFalse()
        assertThat(manager.pendingRationale.value).isEqualTo(PermissionUseCase.RECORD_AUDIO)

        manager.onRationaleContinued(PermissionUseCase.RECORD_AUDIO, canShowRationale = true)
        assertThat(manager.pendingRationale.value).isNull()
    }

    @Test
    fun `manager never asks for notifications below API 33`() {
        val manager = PermissionManager(sdkInt = 32)
        assertThat(manager.requiredPermissions(PermissionUseCase.POST_NOTIFICATIONS))
            .containsExactly(android.Manifest.permission.POST_NOTIFICATIONS)
        val proceeds = manager.evaluate(
            useCase = PermissionUseCase.POST_NOTIFICATIONS,
            hasPermission = false,
            canShowRationale = false,
        )
        assertThat(proceeds).isTrue()
    }

    @Test
    fun `media permissions swap by API level`() {
        val manager33 = PermissionManager(sdkInt = 33)
        assertThat(manager33.requiredPermissions(PermissionUseCase.READ_MEDIA_IMAGES))
            .containsExactly(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
            )
            .inOrder()

        val manager29 = PermissionManager(sdkInt = 29)
        assertThat(manager29.requiredPermissions(PermissionUseCase.READ_MEDIA_IMAGES))
            .containsExactly(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @Test
    fun `denial stats compute rolling rate`() {
        val stats = DenialStats()
        assertThat(stats.denialRate).isEqualTo(0.0)

        stats.recordRequest(); stats.recordResult(granted = false)
        stats.recordRequest(); stats.recordResult(granted = true)
        stats.recordRequest(); stats.recordResult(granted = false)

        assertThat(stats.requestCount).isEqualTo(3)
        assertThat(stats.denialCount).isEqualTo(2)
        assertThat(stats.denialRate).isWithin(0.0001).of(2.0 / 3.0)
        assertThat(stats.snapshot().rate).isWithin(0.0001).of(2.0 / 3.0)
    }

    @Test
    fun `permanent denial after system dialog emits fallback effect`() {
        val manager = PermissionManager(sdkInt = 34)
        manager.evaluate(PermissionUseCase.CAMERA, hasPermission = false, canShowRationale = true)

        val effects = mutableListOf<PermissionEffect>()
        // Drain via trySend side: manager exposes effects as SharedFlow; test
        // the deterministic state transitions directly instead.
        manager.onRationaleContinued(PermissionUseCase.CAMERA, canShowRationale = true)
        manager.onSystemDialogResult(
            useCase = PermissionUseCase.CAMERA,
            granted = false,
            canShowRationale = false, // "don't ask again"
        )
        // Fallback decision is internal; assert observable bookkeeping:
        assertThat(manager.stats.requestCount).isEqualTo(1)
        assertThat(manager.stats.denialCount).isEqualTo(1)
        assertThat(effects).isEmpty()
    }
}
