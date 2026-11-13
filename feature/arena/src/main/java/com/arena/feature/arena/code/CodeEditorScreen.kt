package com.arena.feature.arena.code

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.arena.feature.arena.session.ArenaSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

enum class CodeLanguage(val label: String) {
    KOTLIN("Kotlin"),
    PYTHON("Python"),
    JAVASCRIPT("JavaScript"),
    SQL("SQL"),
    SHELL("Shell"),
}

private val DEFAULT_SNIPPET = """fun main() {
    val arena = "Battle, Direct, Side-by-Side, Agent"
    println("Hello, ${'$'}arena")
}
"""

data class CodeState(
    val language: CodeLanguage = CodeLanguage.KOTLIN,
    val code: String = DEFAULT_SNIPPET,
    val synced: Boolean = false,
    val notice: String? = null,
)

sealed interface CodeIntent {
    data class CodeChanged(val text: String) : CodeIntent
    data class LanguageChanged(val language: CodeLanguage) : CodeIntent
    data object Sync : CodeIntent
    data object ClearNotice : CodeIntent
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    private val session: ArenaSessionRepository,
) : androidx.lifecycle.ViewModel() {

    private val _state = androidx.compose.runtime.mutableStateOf(CodeState())
    val state get() = _state

    fun onIntent(intent: CodeIntent) {
        when (intent) {
            is CodeIntent.CodeChanged -> _state.value = _state.value.copy(code = intent.text, synced = false)
            is CodeIntent.LanguageChanged -> _state.value = _state.value.copy(language = intent.language)
            CodeIntent.Sync -> {
                val snapshot = _state.value
                if (snapshot.code.isBlank()) return
                // Sync back to Arena's coding arena through the typed bridge:
                // switch to a code-specialised model, then hand over the buffer.
                session.switchModel("gpt-5-codex", slot = 0)
                session.sendPrompt("[coding_arena:${snapshot.language.name.lowercase()}]\n${snapshot.code}")
                _state.value = snapshot.copy(synced = true, notice = "Synced to coding arena")
            }
            CodeIntent.ClearNotice -> _state.value = _state.value.copy(notice = null)
        }
    }

}

/**
 * Native Compose code editor: line numbers, lightweight syntax highlighting
 * (regex tokenizer per language), and one-tap sync back to Arena's coding
 * arena through the typed JS bridge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    onBack: () -> Unit,
    viewModel: CodeEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state
    val snackbar = remember { SnackbarHostState() }
    val lineCount = remember(state.code) { state.code.count { it == '\n' } + 1 }

    androidx.compose.runtime.LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.onIntent(CodeIntent.ClearNotice)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Code editor", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.onIntent(CodeIntent.Sync) },
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sync")
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
                .padding(padding),
        ) {
            // Language chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CodeLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = state.language == language,
                        onClick = { viewModel.onIntent(CodeIntent.LanguageChanged(language)) },
                        label = { Text(language.label) },
                    )
                }
            }

            // Editor: gutter + highlighted text field
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            ) {
                // Line-number gutter (monospace, non-editable)
                Column(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .padding(start = 8.dp, end = 4.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    repeat(lineCount) { index ->
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                BasicTextField(
                    value = state.code,
                    onValueChange = { viewModel.onIntent(CodeIntent.CodeChanged(it)) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 18.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = remember(state.language) {
                        syntaxHighlightTransformation(state.language)
                    },
                )
            }
        }
    }
}

// ----------------------------------------------------------------- highlighting

private val KEYWORDS: Map<CodeLanguage, Set<String>> = mapOf(
    CodeLanguage.KOTLIN to setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "for", "while",
        "return", "when", "import", "package", "private", "public", "internal", "suspend",
        "override", "data", "sealed", "enum", "companion", "null", "true", "false",
    ),
    CodeLanguage.PYTHON to setOf(
        "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while",
        "with", "as", "try", "except", "finally", "lambda", "None", "True", "False", "async", "await",
    ),
    CodeLanguage.JAVASCRIPT to setOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "class",
        "import", "export", "default", "async", "await", "new", "this", "null", "true", "false",
    ),
    CodeLanguage.SQL to setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
        "JOIN", "LEFT", "RIGHT", "INNER", "ON", "GROUP", "ORDER", "BY", "LIMIT", "AS",
    ),
    CodeLanguage.SHELL to setOf(
        "echo", "cd", "ls", "grep", "cat", "sudo", "apt", "git", "if", "then", "fi",
        "for", "do", "done", "while", "export", "chmod", "curl", "wget",
    ),
)

private val COMMENT_PREFIX = mapOf(
    CodeLanguage.KOTLIN to "//",
    CodeLanguage.JAVASCRIPT to "//",
    CodeLanguage.PYTHON to "#",
    CodeLanguage.SHELL to "#",
    CodeLanguage.SQL to "--",
)

/**
 * Regex tokenizer producing an [AnnotatedString]. Capped at [MAX_HIGHLIGHT]
 * characters so pathological buffers can't hitch the IME.
 */
private fun highlight(code: String, language: CodeLanguage): AnnotatedString {
    val capped = if (code.length > MAX_HIGHLIGHT) code.substring(0, MAX_HIGHLIGHT) else code
    val keywords = KEYWORDS.getValue(language)
    val commentPrefix = COMMENT_PREFIX.getValue(language)
    val tokenRegex = Regex(
        """("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)""",
    )
    return buildAnnotatedString {
        var index = 0
        // Line comments handled first so keywords inside them stay plain.
        while (index < capped.length) {
            val commentAt = capped.indexOf(commentPrefix, index)
            val nextToken = tokenRegex.find(capped, index)
            if (commentAt >= 0 && (nextToken == null || commentAt <= nextToken.range.first)) {
                withStyle(SpanStyle(color = CommentColor)) {
                    append(capped.substring(commentAt, lineEnd(capped, commentAt)))
                }
                index = lineEnd(capped, commentAt)
                continue
            }
            if (nextToken == null || nextToken.range.first < index) break
            // Text before the token (operators, whitespace) stays default.
            append(capped.substring(index, nextToken.range.first))
            val (string, number, word) = nextToken.destructured
            when {
                string.isNotEmpty() -> withStyle(SpanStyle(color = StringColor)) { append(string) }
                number.isNotEmpty() -> withStyle(SpanStyle(color = NumberColor)) { append(number) }
                word.isNotEmpty() -> {
                    if (word in keywords || (language == CodeLanguage.SQL && word.uppercase() in keywords)) {
                        withStyle(SpanStyle(color = KeywordColor, fontWeight = FontWeight.Bold)) { append(word) }
                    } else {
                        append(word)
                    }
                }
            }
            index = nextToken.range.last + 1
        }
        if (capped.length > index) append(capped.substring(index))
    }
}

private fun lineEnd(text: String, from: Int): Int {
    val nl = text.indexOf('\n', from)
    return if (nl == -1) text.length else nl
}

private fun syntaxHighlightTransformation(language: CodeLanguage): VisualTransformation =
    object : VisualTransformation {
        override fun filter(text: AnnotatedString): TransformedText {
            val highlighted = highlight(text.text, language)
            return TransformedText(highlighted, OffsetMapping.Identity)
        }
    }

// Material-theme-independent token colors (readable on dark surfaces).
private val KeywordColor = androidx.compose.ui.graphics.Color(0xFF7AA2FF)
private val StringColor = androidx.compose.ui.graphics.Color(0xFF7EE787)
private val NumberColor = androidx.compose.ui.graphics.Color(0xFFFFB300)
private val CommentColor = androidx.compose.ui.graphics.Color(0xFF8B949E)

private const val MAX_HIGHLIGHT = 20_000
