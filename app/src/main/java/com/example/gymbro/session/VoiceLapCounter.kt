package com.example.gymbro.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

// What the voice counting is doing (shown on the swim page)
enum class VoiceState {
    OFF,            // turned off by the user (or the page is not on the screen)
    NO_PERMISSION,  // the microphone permission was not given
    UNAVAILABLE,    // no speech recognizer on the phone, or the microphone cannot be used now
    WAITING,        // waiting for someone to speak near the phone
    LISTENING       // listening to the number of laps
}

// Counts the laps of a swim said aloud, in Italian or in English, without touching the phone:
// 1. WAITING: the microphone is read only to measure how loud the sound is (SpeechDetector, on a
//    background thread): nothing is recorded, sent or recognized. When someone says something
//    near the phone (e.g. "GymBro")...
// 2. LISTENING: ...the speech recognizer of the phone listens for a few seconds (the page shows it)
//    and the user says the laps, e.g. "four", "quattro". SpokenNumbers reads the number in the
//    text recognized: it is added to the laps (Listener.onNumber); without a number nothing
//    happens. Then back to 1.
// The recognizer is the one of the phone (on the Pixel: Google, free): the one that works on the
// phone (also offline) if available, otherwise the one online. Used only while the swim page is on
// the screen (start / stop). All the functions must be called from the main thread.
class VoiceLapCounter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener
) {

    interface Listener {
        fun onStateChanged(state: VoiceState)
        fun onNumber(laps: Int, heard: String)
        // listened, but no number was said (heard: the text recognized, if any)
        fun onNothing(heard: String?)
        // the recognizer does not work now (e.g. offline, without the speech language on the phone)
        fun onProblem()
    }

    var state = VoiceState.OFF
        private set

    // How loud the sound near the phone is (0..1) while WAITING: the circle of the microphone
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private var waitJob: Job? = null
    private var timeoutJob: Job? = null
    private var recognizer: SpeechRecognizer? = null

    // The recognizer on the phone (works offline); false after it could not recognize the language
    private var onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    // Android 14+: the recognizer switches between Italian and English by itself; false if it
    // cannot (e.g. the other language is not on the phone)
    private var languageSwitch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    // The language of the phone if it is Italian, otherwise English
    private val language = if (Locale.getDefault().language == "it") ITALIAN else ENGLISH

    fun start() {
        if (state == VoiceState.WAITING || state == VoiceState.LISTENING) return
        when {
            !hasPermission(context) -> setState(VoiceState.NO_PERMISSION)
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context) && !SpeechRecognizer.isRecognitionAvailable(context) ->
                setState(VoiceState.UNAVAILABLE)
            else -> waitForVoice()
        }
    }

    // Microphone and recognizer released (e.g. the page is not on the screen anymore)
    fun stop() {
        waitJob?.cancel()
        timeoutJob?.cancel()
        recognizer?.destroy()
        recognizer = null
        _level.value = 0f
        setState(VoiceState.OFF)
    }

    // Listens at once, without waiting for a word (tap on the microphone, e.g. in a loud pool)
    fun listenNow() {
        if (state != VoiceState.WAITING) return
        val waiting = waitJob
        waiting?.cancel()
        scope.launch {
            waiting?.join()  // the microphone is released first
            if (state == VoiceState.WAITING) listen()
        }
    }


    // --------------------------------------------------------- 1. WAITING FOR A VOICE

    // after: milliseconds to wait (the recognizer releases the microphone)
    private fun waitForVoice(after: Long = 0) {
        setState(VoiceState.WAITING)
        waitJob = scope.launch {
            delay(after)
            // the microphone may be still busy for a moment (e.g. the recognizer releasing it)
            repeat(MIC_TRIES) { attempt ->
                if (awaitSpeech()) {
                    listen()
                    return@launch
                }
                if (attempt < MIC_TRIES - 1) delay(MIC_RETRY_MS)
            }
            // the microphone cannot be used now (e.g. by a phone call)
            setState(VoiceState.UNAVAILABLE)
            listener.onProblem()
        }
    }

    // Reads the microphone until someone stops speaking near the phone (true), in background.
    // false: the microphone cannot be used. Stops when the coroutine is cancelled.
    @SuppressLint("MissingPermission")  // checked in start()
    private suspend fun awaitSpeech(): Boolean = withContext(Dispatchers.IO) {
        val bufferBytes = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            FRAME_SAMPLES * 2 * 4
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes
            )
        } catch (e: Exception) {
            return@withContext false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext false
        }
        val detector = SpeechDetector()
        val samples = ShortArray(FRAME_SAMPLES)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext false
            var spoken = false
            while (!spoken) {
                ensureActive()
                // 20 ms of sound (waits for it)
                val read = record.read(samples, 0, FRAME_SAMPLES)
                if (read < 0) return@withContext false
                val db = SpeechDetector.decibels(samples, read)
                _level.value = detector.level(db)
                spoken = detector.add(db)
            }
            true
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
            _level.value = 0f
        }
    }


    // --------------------------------------------------------- 2. LISTENING TO THE NUMBER

    private fun listen() {
        val recognizer = recognizer ?: createRecognizer()
        if (recognizer == null) {
            setState(VoiceState.UNAVAILABLE)
            return
        }
        setState(VoiceState.LISTENING)
        recognizer.startListening(recognizerIntent())
        // the recognizer stops by itself when the user stops speaking: this is only a safety
        timeoutJob = scope.launch {
            delay(LISTEN_TIMEOUT_MS)
            recognizer.cancel()
            listener.onNothing(null)
            listenAgainLater()
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        val created = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
        } catch (e: Exception) {
            // the recognizer on the phone cannot be used: the one online
            onDevice = false
            null
        } ?: try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
        } catch (e: Exception) {
            null
        }
        created?.setRecognitionListener(recognitionListener)
        recognizer = created
        return created
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // helps the recognizer to write the number words
        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(SpokenNumbers.words))
        if (languageSwitch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf(ITALIAN, ENGLISH))
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            if (state != VoiceState.LISTENING) return
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            val laps = heard?.let { SpokenNumbers.find(it) }
            if (heard != null && laps != null) listener.onNumber(laps, heard) else listener.onNothing(heard)
            listenAgainLater()
        }

        override fun onError(error: Int) {
            if (state != VoiceState.LISTENING) return
            when (error) {
                // nothing said, or not understood
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    listener.onNothing(null)
                    listenAgainLater()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stop()
                    setState(VoiceState.NO_PERMISSION)
                }
                // the recognizer was busy or interrupted: a new one next time, without messages
                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    recognizer?.destroy()
                    recognizer = null
                    listenAgainLater()
                }
                // e.g. the language is not on the phone, offline with the recognizer online: first
                // another way, at once (see tryAnotherWay), then a message
                else -> {
                    val languageProblem = error in LANGUAGE_ERRORS
                    if (tryAnotherWay(languageProblem)) {
                        timeoutJob?.cancel()
                        listen()
                    } else {
                        recognizer?.destroy()
                        recognizer = null
                        listener.onProblem()
                        listenAgainLater()
                    }
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // After an error: first without switching language (the newest feature of the recognizers),
    // then, if the language is not on the phone, with the recognizer online. false: nothing else to try.
    private fun tryAnotherWay(languageProblem: Boolean): Boolean {
        when {
            languageSwitch -> languageSwitch = false
            languageProblem && onDevice && SpeechRecognizer.isRecognitionAvailable(context) -> onDevice = false
            else -> return false
        }
        recognizer?.destroy()
        recognizer = null
        return true
    }

    private fun listenAgainLater() {
        timeoutJob?.cancel()
        waitForVoice(after = MIC_RELEASE_MS)
    }

    private fun setState(state: VoiceState) {
        if (this.state == state) return
        this.state = state
        listener.onStateChanged(state)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = SAMPLE_RATE / 50   // 20 ms
        private const val LISTEN_TIMEOUT_MS = 10_000L
        private const val MIC_RELEASE_MS = 400L
        private const val MIC_TRIES = 3
        private const val MIC_RETRY_MS = 1000L
        private const val ITALIAN = "it-IT"
        private const val ENGLISH = "en-US"
        // SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS (Android 14)
        private const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15

        // The language cannot be recognized on the phone (e.g. not downloaded)
        private val LANGUAGE_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS
        )

        fun hasPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
