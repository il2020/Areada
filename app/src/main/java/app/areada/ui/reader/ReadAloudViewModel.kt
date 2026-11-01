package app.areada.ui.reader

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.areada.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/** How many segments are synthesized ahead of the one being played. */
private const val SynthesizeAhead = 2

/** Size of the WAV header TextToSpeech writes; a file no longer than this holds no audio. */
private const val WavHeaderLength = 44

/**
 * Reads a book aloud independently of the UI, so it keeps going (and reacts to headset buttons)
 * while the screen is off. Screens only observe [state] and tell the player where to start.
 *
 * Speech is synthesized to files and played here rather than by the TTS engine: Android sends
 * headset buttons to the app that plays audio itself, and the engine's audio belongs to its process.
 */
internal class ReadAloudViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    var state by mutableStateOf<ReadAloudState?>(null)
        private set

    /** Chapter the player moved on to by itself; the reader opens it and calls [chapterOpened]. */
    var chapterToOpen by mutableStateOf<Int?>(null)
        private set

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: (() -> Unit)? = null
    private var mediaPlayer: MediaPlayer? = null
    private val audioDir = File(context.cacheDir, "read_aloud")
    // Keeps the CPU awake with the screen off while nothing is audible: chapter loading, synthesis.
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Areada:ReadAloud")
        .apply { setReferenceCounted(false) }
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeOnFocusGain = false
    private var noisyReceiverRegistered = false
    private var serviceRunning = false

    private var book: ReadAloudBook? = null
    private var chapterIndex = 0
    private var segments: List<ReadAloudSegment> = emptyList()
    private var segmentIndex = -1
    /** How the start segment of the current chapter is picked once it has loaded. */
    private var pickStart: (List<ReadAloudSegment>) -> Int = { 0 }
    /** Last segment handed to the engine for synthesis, and the last one whose audio file is ready. */
    private var synthesizedUntil = -1
    private var readyUntil = -1
    /**
     * Whether the audio of [segmentIndex] is in the player, playing or paused mid-way; otherwise
     * it's still being synthesized.
     */
    private var segmentPlaying = false
    private var finished = false
    private var generation = 0
    private var loadJob: Job? = null

    private val synthesisListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
            dispatch(utteranceId) { index ->
                readyUntil = index
                if (!segmentPlaying && segmentIndex <= readyUntil) {
                    playSegment()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            dispatch(utteranceId) { fail(R.string.read_aloud_failed) }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resume()
                }
            }
            // A notification sound, navigation prompt or call: pause mid-phrase and go on afterwards.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false
                if (state?.playing == true) {
                    pausePlayback()
                    resumeOnFocusGain = true
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
                abandonFocus()
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            pause()
        }
    }

    /**
     * Starts reading [book] at [chapterIndex]. When [resumeAllowed], continues from the segment
     * reading was paused on (if it is still there); otherwise starts at [startIndex].
     */
    fun play(
        book: ReadAloudBook,
        chapterIndex: Int,
        resumeAllowed: Boolean,
        startIndex: (List<ReadAloudSegment>) -> Int,
    ) {
        val pausedSegment = state
            ?.takeIf { resumeAllowed && it.documentUri == book.documentUri && it.chapterIndex == chapterIndex }
            ?.segment
        startChapter(book, chapterIndex) { loaded ->
            pausedSegment
                ?.let { segment -> loaded.indexOfFirst { it.start == segment.start && it.text == segment.text } }
                ?.takeIf { it >= 0 }
                ?: startIndex(loaded)
        }
    }

    /** Continues after a pause, e.g. from a headset button. */
    fun resume() {
        val currentBook = book ?: return
        if (state?.playing != false || finished) return
        when {
            segmentPlaying -> if (beginPlayback(currentBook.title)) {
                mediaPlayer?.start()
                updateState(state?.copy(playing = true))
            }
            segmentIndex in segments.indices -> if (beginPlayback(currentBook.title)) {
                speakFrom(segmentIndex)
            }
            // Paused while the chapter was loading: start where the reader originally asked to.
            else -> startChapter(currentBook, chapterIndex, pickStart)
        }
    }

    fun pause() {
        resumeOnFocusGain = false
        pausePlayback()
    }

    /** Restarts reading at the start of [chapterIndex] if the user navigated there while reading. */
    fun followChapter(documentUri: String, chapterIndex: Int) {
        val currentBook = book ?: return
        val current = state ?: return
        if (current.playing && current.documentUri == documentUri && current.chapterIndex != chapterIndex) {
            startChapter(currentBook, chapterIndex) { 0 }
        }
    }

    /** Ends reading of [documentUri] completely, e.g. when its reader is closed. */
    fun stop(documentUri: String) {
        if (state?.documentUri != documentUri) return
        haltSpeech()
        endPlayback()
        book = null
        segments = emptyList()
        segmentIndex = -1
        chapterToOpen = null
        updateState(null)
    }

    fun chapterOpened() {
        chapterToOpen = null
    }

    override fun onCleared() {
        haltSpeech()
        endPlayback()
        tts?.shutdown()
        tts = null
        mediaPlayer?.release()
        mediaPlayer = null
        wakeLock.release()
        mediaSession?.release()
        mediaSession = null
    }

    /** Ends reading but keeps its position, e.g. on Stop from a watch or the assistant. */
    private fun stopPlayback() {
        pause()
        endPlayback()
    }

    /**
     * Stops speaking but keeps focus and the service, so reading can resume in the background.
     * Audio already playing is paused mid-way and continues from there.
     */
    private fun pausePlayback() {
        if (state?.playing != true) return
        if (segmentPlaying) {
            mediaPlayer?.pause()
        } else {
            haltSpeech()
        }
        updateState(state?.copy(playing = false))
    }

    private fun startChapter(
        book: ReadAloudBook,
        chapterIndex: Int,
        pickIndex: (List<ReadAloudSegment>) -> Int,
    ) {
        // Without focus (e.g. during a call) nothing changes, so the paused position survives.
        if (!beginPlayback(book.title)) return
        updateMetadata(book, chapterIndex)
        haltSpeech()
        this.book = book
        this.chapterIndex = chapterIndex
        segments = emptyList()
        segmentIndex = -1
        pickStart = pickIndex
        finished = false
        updateState(ReadAloudState(book.documentUri, chapterIndex, segment = null, playing = true))
        loadJob = viewModelScope.launch {
            val loaded = try {
                withContext(Dispatchers.Default) { book.loadChapter(chapterIndex) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail(R.string.unable_render_section)
                return@launch
            }
            segments = loaded
            if (loaded.isEmpty()) {
                onChapterFinished()
            } else {
                speakFrom(pickIndex(loaded).coerceIn(0, loaded.lastIndex))
            }
        }
    }

    private fun onChapterFinished() {
        val currentBook = book ?: return
        if (chapterIndex + 1 < currentBook.chapterCount) {
            startChapter(currentBook, chapterIndex + 1) { 0 }
            chapterToOpen = chapterIndex
            return
        }
        haltSpeech()
        endPlayback()
        finished = true
        segmentIndex = -1
        updateState(state?.copy(segment = null, playing = false))
    }

    private fun speakFrom(index: Int) {
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeech = { speakFrom(index) }
            if (engine == null) {
                // Init status may arrive synchronously from the constructor, so handle it after assignment.
                tts = TextToSpeech(context) { status -> mainHandler.post { onTtsInit(status) } }
            }
            return
        }
        generation += 1
        segmentIndex = index
        synthesizedUntil = index - 1
        readyUntil = index - 1
        applyLanguage(engine, book?.language)
        updateState(state?.copy(segment = segments[index], playing = true))
        engine.stop()
        synthesizeAhead()
    }

    private fun synthesizeAhead() {
        val engine = tts ?: return
        audioDir.mkdirs()
        while (synthesizedUntil < segments.lastIndex && synthesizedUntil < segmentIndex + SynthesizeAhead) {
            synthesizedUntil += 1
            val result = engine.synthesizeToFile(
                segments[synthesizedUntil].text,
                null,
                audioFile(synthesizedUntil),
                "$generation:$synthesizedUntil",
            )
            if (result != TextToSpeech.SUCCESS) {
                fail(R.string.read_aloud_unavailable)
                return
            }
        }
    }

    private fun audioFile(index: Int) = File(audioDir, "$generation-$index.wav")

    /** Plays [segmentIndex], whose audio file is ready. */
    private fun playSegment() {
        val file = audioFile(segmentIndex)
        if (file.length() <= WavHeaderLength) {
            // Nothing to say, e.g. a "* * *" separator.
            file.delete()
            onSegmentPlayed()
            return
        }
        val player = mediaPlayer ?: MediaPlayer().apply {
            setAudioAttributes(speechAttributes)
            setOnCompletionListener { onSegmentPlayed() }
            setOnErrorListener { _, _, _ ->
                fail(R.string.read_aloud_failed)
                true
            }
        }.also { mediaPlayer = it }
        try {
            player.reset()
            FileInputStream(file).use { player.setDataSource(it.fd) }
            player.prepare()
            player.start()
        } catch (_: Exception) {
            fail(R.string.read_aloud_failed)
            return
        } finally {
            // The player keeps its own descriptor, so the file isn't needed any more.
            file.delete()
        }
        segmentPlaying = true
        updateState(state?.copy(segment = segments[segmentIndex]))
        synthesizeAhead()
    }

    private fun onSegmentPlayed() {
        segmentPlaying = false
        if (segmentIndex == segments.lastIndex) {
            onChapterFinished()
            return
        }
        segmentIndex += 1
        if (segmentIndex <= readyUntil) {
            playSegment()
        } else {
            synthesizeAhead()
        }
    }

    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            engine.shutdown()
            tts = null
            pendingSpeech = null
            fail(R.string.read_aloud_unavailable)
            return
        }
        ttsReady = true
        engine.setOnUtteranceProgressListener(synthesisListener)
        pendingSpeech?.also { pendingSpeech = null }?.invoke()
    }

    private fun applyLanguage(engine: TextToSpeech, language: String?) {
        val locale = language
            ?.takeIf { it.isNotBlank() }
            ?.let { Locale.forLanguageTag(it.replace('_', '-')) }
            ?.takeIf { it.language.isNotEmpty() }
        if (locale != null && engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.setLanguage(locale)
        } else {
            engine.defaultVoice?.let(engine::setVoice)
        }
    }

    /** Stops synthesis and playback and invalidates their pending callbacks. */
    private fun haltSpeech() {
        generation += 1
        pendingSpeech = null
        loadJob?.cancel()
        loadJob = null
        if (ttsReady) {
            tts?.stop()
        }
        mediaPlayer?.reset()
        segmentPlaying = false
        audioDir.listFiles()?.forEach { it.delete() }
    }

    /** Pauses on an error; Play retries from the failed segment. */
    private fun fail(messageRes: Int) {
        haltSpeech()
        updateState(state?.copy(playing = false))
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    /**
     * Takes audio focus (unless it's still held), the media session and the foreground service;
     * false when the system refuses focus (e.g. during a call) or the service (app in the
     * background). Both are kept while paused: on Android 15+ focus is only granted to the top
     * app or one running a foreground service.
     */
    private fun beginPlayback(title: String): Boolean {
        if (!hasFocus) {
            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(speechAttributes)
                    // Speech under another sound gets lost, so the listener pauses instead of ducking.
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                    .also { focusRequest = it }
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                return false
            }
            hasFocus = true
            // Granted directly, so the GAIN of an earlier transient loss won't come any more.
            resumeOnFocusGain = false
        }
        val session = mediaSession ?: MediaSession(context, "AreadaReadAloud").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = resume()

                    override fun onPause() = pause()

                    override fun onStop() = stopPlayback()
                },
            )
        }.also { mediaSession = it }
        if (!serviceRunning) {
            try {
                ReadAloudService.start(context, title, session.sessionToken)
            } catch (_: IllegalStateException) {
                // Android 12+ refuses a foreground service to an app in the background.
                abandonFocus()
                return false
            }
            serviceRunning = true
        }
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyReceiverRegistered = true
        }
        session.isActive = true
        return true
    }

    /**
     * Book and chapter for the media notification. The artist is its second line: the chapter
     * title, or the app name when there's none, since some devices print a missing artist as "null".
     */
    private fun updateMetadata(book: ReadAloudBook, chapterIndex: Int) {
        val chapterTitle = book.chapterTitle(chapterIndex)?.takeIf { it.isNotBlank() && it != book.title }
        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, book.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, chapterTitle ?: context.getString(R.string.app_name))
                .build(),
        )
    }

    /** Reading is over: gives audio focus back and stops the foreground service. */
    private fun endPlayback() {
        abandonFocus()
        if (noisyReceiverRegistered) {
            context.unregisterReceiver(noisyReceiver)
            noisyReceiverRegistered = false
        }
        if (serviceRunning) {
            ReadAloudService.stop(context)
            serviceRunning = false
        }
    }

    private fun abandonFocus() {
        hasFocus = false
        resumeOnFocusGain = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    // Held exactly while reading; it's released as soon as reading stops, hence no timeout.
    @SuppressLint("WakelockTimeout")
    private fun updateState(newState: ReadAloudState?) {
        state = newState
        if (newState?.playing == true) {
            wakeLock.acquire()
        } else {
            wakeLock.release()
        }
        val session = mediaSession ?: return
        if (newState == null) {
            session.isActive = false
            return
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (newState.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
    }

    private fun dispatch(utteranceId: String, action: (Int) -> Unit) {
        val utteranceGeneration = utteranceId.substringBefore(':').toIntOrNull() ?: return
        val index = utteranceId.substringAfter(':').toIntOrNull() ?: return
        mainHandler.post {
            if (utteranceGeneration == generation && index in segments.indices) {
                action(index)
            }
        }
    }
}
