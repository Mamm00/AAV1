package com.arena.feature.arena.agent

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.core.permissions.PermissionManager
import com.arena.core.permissions.PermissionUseCase
import com.arena.core.sse.ArenaEvent
import com.arena.feature.arena.components.BashOutputView
import com.arena.feature.arena.permission.PermissionRationaleSheet
import com.arena.feature.arena.permission.rememberPermissionController
import javax.inject.Inject

/**
 * Agent Mode — full tool-access interface:
 *  - web search / file / image-generation toggles (thumb zone),
 *  - native vertical stepper timeline (Web Search → File Processing →
 *    Code Execution → Report) fed by SSE `a3:` agent events,
 *  - monospace scrollable Bash output,
 *  - foreground dataSync service while a long task runs (Android 14+ type
 *    declared in the app manifest).
 *
 * RECORD_AUDIO (voice note) and CAMERA (take photo) are requested only when
 * their buttons are tapped, with the pre-permission rationale sheet first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentModeScreen(
    onBack: () -> Unit,
    viewModel: AgentModeViewModel = hiltViewModel(),
    permissionManager: PermissionManager = hiltViewModel<AgentPermissionHolder>().manager,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val permissionController = rememberPermissionController(permissionManager, snackbar)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AgentEffect.ShowNotice -> snackbar.showSnackbar(effect.message)
                AgentEffect.StartForegroundTask -> {
                    val intent = Intent(context, AgentTaskService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
                AgentEffect.StopForegroundTask -> {
                    context.stopService(Intent(context, AgentTaskService::class.java))
                }
            }
        }
    }

    // Pre-permission rationale sheet (only visible when pendingRationale set).
    permissionController.pendingRationale.value?.let { useCase ->
        PermissionRationaleSheet(
            useCase = useCase,
            onContinue = { permissionController.onRationaleContinue(useCase) },
            onDismiss = { permissionController.onRationaleDismiss() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Task timeline (vertical stepper) ----
            if (state.steps.isNotEmpty()) {
                Text("Task timeline", style = MaterialTheme.typography.titleMedium)
                state.steps.forEach { step -> StepRow(step) }
                HorizontalDivider()
            }

            // ---- Bash output (monospace, scrollable, selectable) ----
            if (state.bashOutput.isNotBlank() || state.bashStderr.isNotBlank()) {
                Text("Bash output", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.selection.SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.bashOutput.isNotBlank()) {
                            BashOutputView(output = state.bashOutput)
                        }
                        if (state.bashStderr.isNotBlank()) {
                            BashOutputView(output = state.bashStderr, isStderr = true)
                        }
                    }
                }
                HorizontalDivider()
            }

            // ---- Final report ----
            if (state.finalAnswer.isNotBlank()) {
                Text("Report", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = state.finalAnswer,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(8.dp))

            // ---- Tool toggles ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.webSearchEnabled,
                    onClick = { viewModel.onIntent(AgentIntent.ToggleTool("web")) },
                    label = { Text("Web search") },
                )
                FilterChip(
                    selected = state.filesEnabled,
                    onClick = { viewModel.onIntent(AgentIntent.ToggleTool("files")) },
                    label = { Text("Files") },
                )
                FilterChip(
                    selected = state.imageGenEnabled,
                    onClick = { viewModel.onIntent(AgentIntent.ToggleTool("image")) },
                    label = { Text("Image gen") },
                )
            }

            // ---- Prompt + run (thumb zone) ----
            OutlinedTextField(
                value = state.prompt,
                onValueChange = { viewModel.onIntent(AgentIntent.PromptChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Give the agent a task…") },
                minLines = 2,
                maxLines = 5,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Contextual: only asks for RECORD_AUDIO when tapped.
                FilledTonalButton(
                    onClick = { permissionController.request(PermissionUseCase.RECORD_AUDIO) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text("Voice note") }

                // Contextual: CAMERA only when the user wants to snap a photo.
                FilledTonalButton(
                    onClick = { permissionController.request(PermissionUseCase.CAMERA) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text("Take photo") }

                if (state.running) {
                    Button(
                        onClick = { viewModel.onIntent(AgentIntent.Stop) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = { viewModel.onIntent(AgentIntent.Run) },
                        enabled = state.prompt.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Run")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepRow(step: AgentStepUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val (icon, tint) = when (step.status) {
            ArenaEvent.AgentStep.Status.DONE ->
                Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
            ArenaEvent.AgentStep.Status.FAILED ->
                Icons.Filled.Error to MaterialTheme.colorScheme.error
            ArenaEvent.AgentStep.Status.RUNNING ->
                Icons.Filled.Circle to MaterialTheme.colorScheme.secondary
            ArenaEvent.AgentStep.Status.PENDING ->
                Icons.Filled.Circle to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = step.status.name, tint = tint, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            step.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = step.status.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tiny holder so the screen can reach the singleton PermissionManager without
 * exposing Hilt internals to call sites.
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class AgentPermissionHolder @Inject constructor(
    val manager: PermissionManager,
) : androidx.lifecycle.ViewModel()
