package com.arena.feature.arena.direct

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.feature.arena.components.RankBadge
import com.arena.feature.arena.components.ResponseCard
import com.arena.feature.arena.components.rememberArenaHaptics
import com.arena.core.permissions.PermissionManager
import com.arena.core.permissions.PermissionUseCase
import com.arena.feature.arena.home.HomePromptBus
import com.arena.feature.arena.modelpicker.ModelPickerBottomSheet
import com.arena.feature.arena.permission.PermissionRationaleSheet
import com.arena.feature.arena.permission.rememberPermissionController
import javax.inject.Inject

/**
 * Direct Mode: searchable 300+ model picker, streaming response cards with
 * collapsible reasoning, hold-to-talk dictation with live waveform, and file
 * attachment via the Storage Access Framework (no storage permission).
 *
 * Thumb-zone rule: send / mic / attach all live in the bottom 40% with ≥48dp
 * targets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectChatScreen(
    onBack: () -> Unit,
    viewModel: DirectChatViewModel = hiltViewModel(),
    permissionManager: PermissionManager = hiltViewModel<DirectPermissionHolder>().manager,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = rememberArenaHaptics()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val permissionController = rememberPermissionController(permissionManager, snackbar)
    val listState = rememberLazyListState()

    var menuMessageId by remember { mutableStateOf<String?>(null) }
    var dictator by remember { mutableStateOf<VoiceDictator?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            } ?: "attachment"
            var size = 0L
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) size = it.getLong(0)
            }
            viewModel.onIntent(DirectIntent.AttachChosen(name, mime, size, uri.toString()))
        }
    }

    // Clipboard hand-off from Home (local, never persisted).
    LaunchedEffect(Unit) {
        HomePromptBus.consume()?.let { viewModel.onIntent(DirectIntent.ConsumeHomePrompt(it)) }
    }

    // One-shot effects.
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DirectEffect.CopyToClipboard -> {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(effect.text))
                    snackbar.showSnackbar("Copied")
                }
                is DirectEffect.ShowNotice -> snackbar.showSnackbar(effect.message)
                DirectEffect.OpenFilePicker -> filePicker.launch(arrayOf("*/*"))
            }
        }
    }

    // Auto-scroll to newest message.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    DisposableEffect(dictator) {
        onDispose { dictator?.destroy() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Direct", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(12.dp))
                        // Model chip → bottom sheet switcher (thumb-reachable via sheet).
                        Text(
                            text = state.selectedModel?.let { "· ${it.displayName}" } ?: "· picking…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .pointerInput(Unit) {
                                    detectTapGestures { viewModel.onIntent(DirectIntent.TogglePicker) }
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        state.selectedModel?.rankLabel?.let { RankBadge(it) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.pageReady) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                        )
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
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    Box {
                        if (message.role == "user") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(
                                    text = message.content,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                        .semantics { contentDescription = "Your message" },
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        } else {
                            ResponseCard(
                                author = message.author,
                                content = if (message.streaming && message.content.isEmpty()) {
                                    "…"
                                } else {
                                    message.content
                                },
                                reasoning = message.reasoning,
                                onLongPress = {
                                    haptics(HapticFeedbackType.LongPress)
                                    menuMessageId = message.id
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = menuMessageId == message.id,
                            onDismissRequest = { menuMessageId = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                onClick = {
                                    menuMessageId = null
                                    viewModel.onIntent(DirectIntent.CopyMessage(message.id))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    menuMessageId = null
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, message.content)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share response"))
                                },
                            )
                            if (message.role == "assistant") {
                                DropdownMenuItem(
                                    text = { Text("Regenerate") },
                                    onClick = {
                                        menuMessageId = null
                                        viewModel.onIntent(DirectIntent.Regenerate(message.id))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ---- Thumb-zone input bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Hold-to-talk mic with live waveform feedback.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .pointerInput(state.listening) {
                            detectTapGestures(
                                onPress = {
                                    haptics(HapticFeedbackType.LongPress)
                                    // RECORD_AUDIO is requested only here — never at launch.
                                    if (!permissionController.hasPermission(PermissionUseCase.RECORD_AUDIO)) {
                                        permissionController.request(PermissionUseCase.RECORD_AUDIO)
                                        return@detectTapGestures
                                    }
                                    viewModel.onIntent(DirectIntent.DictationStart)
                                    dictator = VoiceDictator(
                                        context = context,
                                        onFinal = { text ->
                                            viewModel.onIntent(DirectIntent.DictationFinal(text))
                                        },
                                        onLevel = { level ->
                                            viewModel.onIntent(DirectIntent.DictationLevel(level))
                                        },
                                        onError = { _, _ ->
                                            viewModel.onIntent(DirectIntent.DictationCancel)
                                        },
                                    ).also { it.start() }
                                    tryAwaitRelease()
                                    if (viewModel.state.value.listening) {
                                        dictator?.stop()
                                    }
                                },
                            )
                        }
                        .background(
                            if (state.listening) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Hold to dictate",
                        tint = if (state.listening) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                // Waveform while listening (0..1 level → bar heights).
                if (state.listening) {
                    VoiceWaveform(level = state.voiceLevel, modifier = Modifier.width(56.dp))
                }

                OutlinedTextField(
                    value = state.input,
                    onValueChange = { viewModel.onIntent(DirectIntent.InputChanged(it)) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask anything") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                )

                IconButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Attach file")
                }

                FilledIconButton(
                    onClick = {
                        haptics(HapticFeedbackType.TextHandleMove)
                        viewModel.onIntent(DirectIntent.Send)
                    },
                    enabled = state.input.isNotBlank() && !state.isStreaming,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Send prompt" },
                ) {
                    if (state.isStreaming) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }

        permissionController.pendingRationale.value?.let { useCase ->
            PermissionRationaleSheet(
                useCase = useCase,
                onContinue = { permissionController.onRationaleContinue(useCase) },
                onDismiss = { permissionController.onRationaleDismiss() },
            )
        }

        if (state.pickerOpen) {
            ModelPickerBottomSheet(
                models = state.models,
                recommended = emptyList(),
                selectedModelId = state.selectedModel?.id,
                onModelSelected = { viewModel.onIntent(DirectIntent.SelectModel(it)) },
                onDismiss = { viewModel.onIntent(DirectIntent.TogglePicker) },
            )
        }
    }
}

@Composable
private fun VoiceWaveform(level: Float, modifier: Modifier = Modifier) {
    val bars = 6
    Row(
        modifier = modifier.height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(bars) { index ->
            val phase = (level * (1f - index * 0.12f)).coerceIn(0.08f, 1f)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((phase * 28).dp.coerceAtLeast(4.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}


/**
 * Holds the singleton PermissionManager for screens that request
 * contextual permissions (mic, camera, notifications).
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class DirectPermissionHolder @Inject constructor(
    val manager: PermissionManager,
) : androidx.lifecycle.ViewModel()
