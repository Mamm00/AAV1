package com.arena.feature.arena.imageeditor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.core.permissions.PermissionManager
import com.arena.core.permissions.PermissionUseCase
import com.arena.feature.arena.permission.PermissionRationaleSheet
import com.arena.feature.arena.permission.rememberPermissionController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

enum class ImageFilter(val label: String) {
    ORIGINAL("Original"),
    GRAYSCALE("Mono"),
    SEPIA("Sepia"),
    WARM("Warm"),
    COOL("Cool"),
}

enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1f),
    WIDE("16:9", 16f / 9f),
    PORTRAIT("4:5", 4f / 5f),
}

data class EditorState(
    val source: ImageBitmap? = null,
    val filter: ImageFilter = ImageFilter.ORIGINAL,
    val aspect: CropAspect = CropAspect.FREE,
    val notice: String? = null,
)

sealed interface EditorIntent {
    data class ImageLoaded(val bitmap: Bitmap) : EditorIntent
    data class FilterSelected(val filter: ImageFilter) : EditorIntent
    data class AspectSelected(val aspect: CropAspect) : EditorIntent
    data object Rotate : EditorIntent
    data object ClearNotice : EditorIntent
    data object SendToModel : EditorIntent
}

sealed interface EditorEffect {
    data class ShowNotice(val message: String) : EditorEffect
    data class SendImage(val file: File, val filter: ImageFilter) : EditorEffect
}

/**
 * Native Android Bitmap pipeline: crop (aspect), rotate, filters (ColorMatrix)
 * and send-to-model. Single-image selection uses the Android Photo Picker
 * (no permission); camera capture requests CAMERA only on tap with the
 * pre-permission rationale sheet and falls back to the picker on denial.
 */
@HiltViewModel
class ImageEditorViewModel @Inject constructor() :
    androidx.lifecycle.ViewModel() {

    private val _state = androidx.compose.runtime.mutableStateOf(EditorState())
    val state: androidx.compose.runtime.State<EditorState> get() = _state

    private var working: Bitmap? = null

    fun onIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.ImageLoaded -> {
                working = intent.bitmap
                _state.value = EditorState(source = intent.bitmap.asImageBitmap())
            }
            is EditorIntent.FilterSelected -> {
                _state.value = _state.value.copy(filter = intent.filter)
            }
            is EditorIntent.AspectSelected -> {
                _state.value = _state.value.copy(aspect = intent.aspect)
            }
            EditorIntent.Rotate -> {
                val src = working ?: return
                val rotated = rotate90(src)
                working = rotated
                _state.value = _state.value.copy(source = rotated.asImageBitmap())
            }
            EditorIntent.ClearNotice -> _state.value = _state.value.copy(notice = null)
            EditorIntent.SendToModel -> {
                val src = working ?: run {
                    _state.value = _state.value.copy(notice = "Choose or capture an image first")
                    return
                }
                val composited = applyPipeline(src, _state.value.filter, _state.value.aspect)
                val file = File.createTempFile("arena_edit_", ".jpg", null)
                FileOutputStream(file).use { out ->
                    composited.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                _state.value = _state.value.copy(notice = "Ready to send")
                pendingSend = EditorEffect.SendImage(file, _state.value.filter)
            }
        }
    }

    /** Pulled by the screen after SendToModel to fire navigation/session hand-off. */
    var pendingSend: EditorEffect.SendImage? = null

    fun consumePendingSend(): EditorEffect.SendImage? = pendingSend.also { pendingSend = null }

    // -------------------------------------------------- Bitmap pipeline -- //

    private fun rotate90(src: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun applyPipeline(src: Bitmap, filter: ImageFilter, aspect: CropAspect): Bitmap {
        val cropped = cropToAspect(src, aspect.ratio)
        return applyFilter(cropped, filter)
    }

    private fun cropToAspect(src: Bitmap, ratio: Float?): Bitmap {
        if (ratio == null) return src
        val (targetW, targetH) = if (src.width.toFloat() / src.height > ratio) {
            (src.height * ratio).toInt() to src.height
        } else {
            src.width to (src.width / ratio).toInt()
        }
        val left = ((src.width - targetW) / 2).coerceAtLeast(0)
        val top = ((src.height - targetH) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, left, top, targetW, targetH)
    }

    private fun applyFilter(src: Bitmap, filter: ImageFilter): Bitmap {
        if (filter == ImageFilter.ORIGINAL) return src
        val matrix = ColorMatrix()
        when (filter) {
            ImageFilter.GRAYSCALE -> matrix.setSaturation(0f)
            ImageFilter.SEPIA -> {
                val sepia = ColorMatrix(
                    floatArrayOf(
                        0.393f, 0.769f, 0.189f, 0f, 0f,
                        0.349f, 0.686f, 0.168f, 0f, 0f,
                        0.272f, 0.534f, 0.131f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
                matrix.postConcat(sepia)
            }
            ImageFilter.WARM -> matrix.setConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.1f, 0f, 0f, 0f, 10f,
                        0f, 1.0f, 0f, 0f, 4f,
                        0f, 0f, 0.9f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
                ColorMatrix(),
            )
            ImageFilter.COOL -> matrix.setConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.9f, 0f, 0f, 0f, 0f,
                        0f, 1.0f, 0f, 0f, 4f,
                        0f, 0f, 1.1f, 0f, 10f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
                ColorMatrix(),
            )
            ImageFilter.ORIGINAL -> Unit
        }
        val out = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeImageEditorScreen(
    onBack: () -> Unit,
    onSendToChat: (File) -> Unit = {},
    viewModel: ImageEditorViewModel = hiltViewModel(),
    permissionManager: PermissionManager? = null,
) {
    val state by viewModel.state
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val manager = permissionManager ?: remember { PermissionManager() }
    val permissionController = rememberPermissionController(manager, snackbar)

    // Photo Picker — no runtime permission required (preferred path).
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) viewModel.onIntent(EditorIntent.ImageLoaded(bitmap))
            }
        }
    }

    // Camera capture via FileProvider; CAMERA permission is requested only if
    // the user taps Take photo (rationale sheet first).
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) viewModel.onIntent(EditorIntent.ImageLoaded(bitmap))
            }
        }
        pendingCameraUri = null
    }

    LaunchedEffect(Unit) {
        // Drain one-shot send hand-off.
        while (true) {
            val send = viewModel.consumePendingSend()
            if (send != null) {
                onSendToChat(send.file)
                snackbar.showSnackbar("Sent to model (${send.filter.label})")
            } else {
                kotlinx.coroutines.delay(200)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image editor", fontWeight = FontWeight.SemiBold) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Preview
            val source = state.source
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (source != null) {
                    Image(
                        bitmap = source,
                        contentDescription = "Edited image preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = "Choose or capture an image first",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Filter + crop chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImageFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.onIntent(EditorIntent.FilterSelected(filter)) },
                        label = { Text(filter.label) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CropAspect.entries.forEach { aspect ->
                    FilterChip(
                        selected = state.aspect == aspect,
                        onClick = { viewModel.onIntent(EditorIntent.AspectSelected(aspect)) },
                        label = { Text(aspect.label) },
                        leadingIcon = if (aspect != CropAspect.FREE) {
                            { Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }

            // ---- Thumb-zone actions ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { viewModel.onIntent(EditorIntent.Rotate) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Rotate90DegreesCw, contentDescription = "Rotate")
                }
                Button(
                    onClick = {
                        // Contextual CAMERA request — rationale sheet first;
                        // denial falls back to the photo picker (no permission).
                        if (permissionController.hasPermission(PermissionUseCase.CAMERA)) {
                            val file = File(context.cacheDir, "captures").apply { mkdirs() }
                                .resolve("cap_${System.currentTimeMillis()}.jpg")
                            pendingCameraUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            takePicture.launch(pendingCameraUri)
                        } else {
                            permissionController.request(PermissionUseCase.CAMERA)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Take photo")
                }
                Button(
                    onClick = {
                        pickMedia.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text("Choose") }

                Button(
                    onClick = { viewModel.onIntent(EditorIntent.SendToModel) },
                    enabled = state.source != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Send")
                }
            }
        }
    }
}
