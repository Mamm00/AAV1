package com.arena.feature.arena.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.arena.core.permissions.Fallback
import com.arena.core.permissions.PermissionEffect
import com.arena.core.permissions.PermissionManager
import com.arena.core.permissions.PermissionUseCase

/**
 * Compose glue for [PermissionManager]:
 *
 *  1. Screen calls [request] at the moment of first use (never at launch).
 *  2. Manager decides → rationale sheet renders (pre-permission explanation).
 *  3. User taps Continue → system dialog fires.
 *  4. Result is fed back; fallbacks (Photo Picker, keyboard…) surface as
 *     snackbars so the feature stays usable without the permission.
 */
class PermissionController internal constructor(
    private val manager: PermissionManager,
    private val launcher: ActivityResultLauncher<Array<String>>,
    private val context: Context,
    private val snackbar: SnackbarHostState?,
) {

    /** Non-null while the pre-permission rationale sheet should show. */
    val pendingRationale get() = manager.pendingRationale

    /** Set while a system dialog launched by this controller is in flight. */
    private var pendingSystemUseCase: PermissionUseCase? = null

    /** True when [useCase]'s permission already holds. */
    fun hasPermission(useCase: PermissionUseCase): Boolean =
        ContextCompat.checkSelfPermission(context, useCase.permission) ==
        PackageManager.PERMISSION_GRANTED

    private fun canShowRationale(useCase: PermissionUseCase): Boolean {
        val activity = context.findActivity() ?: return true
        return manager.requiredPermissions(useCase).any {
            activity.shouldShowRequestPermissionRationale(it)
        }
    }

    /** Entry point: user just tapped the action that needs [useCase]. */
    fun request(useCase: PermissionUseCase) {
        manager.evaluate(
            useCase = useCase,
            hasPermission = hasPermission(useCase),
            canShowRationale = canShowRationale(useCase),
        )
    }

    fun onRationaleContinue(useCase: PermissionUseCase) {
        manager.onRationaleContinued(useCase, canShowRationale(useCase))
    }

    fun onRationaleDismiss() = manager.onRationaleDismissed()

    fun launchSystemDialog(useCase: PermissionUseCase) {
        pendingSystemUseCase = useCase
        launcher.launch(manager.requiredPermissions(useCase).toTypedArray())
    }

    /** Invoked by the activity-result callback with the system dialog output. */
    fun deliverSystemResult(results: Map<String, Boolean>) {
        val useCase = pendingSystemUseCase ?: return
        pendingSystemUseCase = null
        val granted = results.isNotEmpty() && results.values.all { it }
        // Re-read rationale AFTER the result: false + prior denial = blocked.
        manager.onSystemDialogResult(
            useCase = useCase,
            granted = granted,
            canShowRationale = canShowRationale(useCase),
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Remember a [PermissionController] bound to the activity result API.
 * [snackbar] receives fallback messages ("Photo picker opened instead").
 */
@Composable
fun rememberPermissionController(
    manager: PermissionManager,
    snackbar: SnackbarHostState? = null,
): PermissionController {
    val context = LocalContext.current

    // Controller ↔ launcher are mutually dependent: create a holder first,
    // then bind the launcher callback to it.
    val holder = remember { arrayOfNulls<PermissionController>(1) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        holder[0]?.deliverSystemResult(results)
    }
    val controller = remember(manager, launcher) {
        PermissionController(manager, launcher, context, snackbar).also { holder[0] = it }
    }

    // Manager effects: fallback notices (rationale/dialog handled inline).
    LaunchedEffect(manager, snackbar) {
        manager.effects.collect { effect ->
            if (effect is PermissionEffect.Fallback) {
                snackbar?.showSnackbar(fallbackMessage(effect.fallback))
            }
        }
    }

    return controller
}

internal fun fallbackMessage(fallback: Fallback): String = when (fallback) {
    Fallback.PHOTO_PICKER -> "Camera blocked — tap Choose image instead"
    Fallback.KEYBOARD_INPUT -> "Mic blocked — type your prompt instead"
    Fallback.STORAGE_ACCESS_FRAMEWORK -> "Media blocked — use the file picker instead"
    Fallback.IN_APP_TASK_CENTER -> "Notifications blocked — track tasks inside the app"
}
