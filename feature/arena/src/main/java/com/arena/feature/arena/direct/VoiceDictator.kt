package com.arena.feature.arena.direct

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hold-to-talk dictation via [SpeechRecognizer].
 *
 * Permission flow: RECORD_AUDIO is requested only when the user presses the
 * mic (PermissionUseCase.RECORD_AUDIO with pre-rationale sheet). If denied,
 * the input bar falls back to keyboard entry — dictation is never required.
 *
 * Recognition starts on [start] and stops (or cancels) on [stop]/[cancel];
 * partial results stream back so the UI can show a live waveform.
 */
class VoiceDictator(
    context: Context,
    private val onPartial: (String) -> Unit = {},
    private val onFinal: (String) -> Unit,
    private val onLevel: (Float) -> Unit = {},
    onError: (message: String, permanent: Boolean) -> Unit = { _, _ -> },
) : RecognitionListener {

    /** Kept under a distinct name: [onError] as a member resolves to the
     *  RecognitionListener override, not this constructor callback. */
    private val notifyError = onError

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    val isListening: Boolean get() = listening

    fun start(language: String = java.util.Locale.getDefault().toLanguageTag()) {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            notifyError("Voice input isn't available on this device", true)
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also { it.setRecognitionListener(this) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Never prompt the user for a longer recording than they asked for.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        }
        listening = true
        recognizer?.startListening(intent)
    }

    /** User released the mic: let the recognizer finalize. */
    fun stop() {
        if (!listening) return
        recognizer?.stopListening()
    }

    /** Abort immediately (permission revoked, screen closed). */
    fun cancel() {
        listening = false
        recognizer?.cancel()
        destroy()
    }

    fun destroy() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    // ------------------------------------------------------- RecognitionListener

    override fun onResults(results: Bundle?) {
        listening = false
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (text.isNotBlank()) onFinal(text) else notifyError("Didn't catch that", false)
        destroy()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.let(onPartial)
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Map typical -2..10 dB range to 0f..1f for the waveform.
        onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
    }

    override fun onError(error: Int) {
        listening = false
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                notifyError("Microphone permission denied", true)
            SpeechRecognizer.ERROR_NO_MATCH ->
                notifyError("Didn't catch that", false)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                notifyError("Voice service busy — try again", false)
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            -> notifyError("Voice input failed — check your connection", false)
            else -> notifyError("Voice input failed", false)
        }
        destroy()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onEndOfSpeech() = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
