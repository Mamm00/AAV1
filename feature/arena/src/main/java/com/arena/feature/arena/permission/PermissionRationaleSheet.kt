package com.arena.feature.arena.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arena.core.permissions.PermissionUseCase

/**
 * Pre-permission rationale sheet (spec: show a ModalBottomSheet explaining
 * why the permission improves the experience; trigger the system dialog only
 * after the user taps Continue).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionRationaleSheet(
    useCase: PermissionUseCase,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, body, icon) = when (useCase) {
        PermissionUseCase.Camera -> Triple(
            "Camera access",
            "Used only when you tap Take photo, so you can attach a fresh picture. " +
                "The system photo picker always remains available as a fallback.",
            Icons.Filled.CameraAlt,
        )
        PermissionUseCase.RECORD_AUDIO -> Triple(
            "Microphone access",
            "Used only while you hold the mic to dictate a prompt or record an agent " +
                "voice note. Recording never starts before you press and hold.",
            Icons.Filled.GraphicEq,
        )
        PermissionUseCase.READ_MEDIA_IMAGES -> Triple(
            "Media access",
            "Only needed to browse your full photo library. Single picks use the " +
                "system photo picker — no permission required.",
            Icons.Filled.PhotoLibrary,
        )
        PermissionUseCase.POST_NOTIFICATIONS -> Triple(
            "Notifications",
            "Lets you vote on Battle results from a notification and know when a long " +
                "Agent task finishes — even when the app is closed.",
            Icons.Filled.Notifications,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                    .padding(10.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text("Not now") }
                FilledTonalButton(
                    onClick = onContinue,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text("Continue") }
            }
        }
    }
}
