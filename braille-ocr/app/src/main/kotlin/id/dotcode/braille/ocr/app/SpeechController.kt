package id.dotcode.braille.ocr.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

/**
 * Android text-to-speech behind a small state model. A long document is spoken in chunks
 * below the engine's input limit; progress is tracked per word so "pause" can resume from
 * the word it stopped at (the platform engine itself has no pause).
 */
class SpeechController(context: Context) {

    enum class Availability { INITIALIZING, READY, UNAVAILABLE }

    /** One piece of text being played, identified by [key] so each screen tracks its own. */
    data class Playback(
        val key: String,
        val speaking: Boolean,
        /** Character offset of the word being spoken, or where playback paused. */
        val position: Int,
        val length: Int,
        /** The engine refused to speak (e.g. the voice isn't downloaded yet). */
        val failed: Boolean = false,
    ) {
        val fraction: Float get() = if (length == 0) 0f else (position.toFloat() / length).coerceIn(0f, 1f)
        val finished: Boolean get() = !speaking && position >= length
    }

    private val _availability = MutableStateFlow(Availability.INITIALIZING)
    val availability: StateFlow<Availability> = _availability.asStateFlow()

    /** Whether the requested voice language had to fall back to the engine default. */
    private val _languageMissing = MutableStateFlow(false)
    val languageMissing: StateFlow<Boolean> = _languageMissing.asStateFlow()

    private val _playback = MutableStateFlow<Playback?>(null)
    val playback: StateFlow<Playback?> = _playback.asStateFlow()

    private val cacheDir: File = context.applicationContext.cacheDir
    private var text: String = ""

    // Read from the engine's binder thread in the progress listener.
    @Volatile private var chunkStarts: List<Int> = emptyList()
    @Volatile private var session = 0
    private var currentLocale: Locale? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        _availability.value = if (status == TextToSpeech.SUCCESS) Availability.READY else Availability.UNAVAILABLE
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val (id, chunk) = parse(utteranceId) ?: return
                if (id != session) return
                val absolute = chunkStarts.getOrElse(chunk) { 0 } + start
                _playback.update { it?.copy(position = absolute) }
            }

            override fun onDone(utteranceId: String) {
                val (id, chunk) = parse(utteranceId) ?: return
                if (id != session) return
                if (chunk == chunkStarts.lastIndex) {
                    _playback.update { it?.copy(speaking = false, position = it.length) }
                } else {
                    _playback.update { it?.copy(position = chunkStarts[chunk + 1]) }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(utteranceId: String, errorCode: Int) {
                val (id, _) = parse(utteranceId) ?: return
                if (id == session) _playback.update { it?.copy(speaking = false, failed = true) }
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) = Unit
        })
    }

    /**
     * Speaks [fullText] under [key], starting at [from]. Anything already playing is
     * replaced, including another screen's playback.
     */
    fun speak(key: String, fullText: String, locale: Locale, rate: Float, from: Int = 0) {
        if (_availability.value != Availability.READY || fullText.isBlank()) return
        applyVoice(locale, rate)
        session++
        text = fullText
        val start = from.coerceIn(0, fullText.length).let { if (it >= fullText.length) 0 else it }
        val chunks = chunk(fullText, start)
        chunkStarts = chunks.map { it.first }
        _playback.value = Playback(key, speaking = true, position = start, length = fullText.length)
        tts.stop()
        val queued = chunks.withIndex().all { (index, chunk) ->
            tts.speak(chunk.second, TextToSpeech.QUEUE_ADD, null, "$session:$index") == TextToSpeech.SUCCESS
        }
        if (!queued) {
            session++
            tts.stop()
            _playback.update { it?.copy(speaking = false, failed = true) }
        }
    }

    /** Pauses [key]'s playback, keeping its position for [resume]. */
    fun pause(key: String) {
        if (_playback.value?.key != key) return
        session++
        tts.stop()
        _playback.update { it?.copy(speaking = false) }
    }

    fun resume(key: String, locale: Locale, rate: Float) {
        val current = _playback.value?.takeIf { it.key == key } ?: return
        speak(key, text, locale, rate, from = current.position)
    }

    /** Stops everything; [key] limits it to one screen's playback. */
    fun stop(key: String? = null) {
        if (key != null && _playback.value?.key != key) return
        session++
        tts.stop()
        _playback.value = null
    }

    enum class VoiceData { READY, MISSING, UNSUPPORTED }

    /** Whether [locale] can be spoken offline right now. */
    fun voiceData(locale: Locale): VoiceData {
        if (_availability.value != Availability.READY) return VoiceData.MISSING
        return when (tts.isLanguageAvailable(locale)) {
            TextToSpeech.LANG_NOT_SUPPORTED -> VoiceData.UNSUPPORTED
            TextToSpeech.LANG_MISSING_DATA -> VoiceData.MISSING
            else -> {
                val offline = runCatching { tts.voices }.getOrNull().orEmpty().any { voice ->
                    voice.locale.language == locale.language &&
                        !voice.isNetworkConnectionRequired &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
                }
                if (offline) VoiceData.READY else VoiceData.MISSING
            }
        }
    }

    /**
     * Asks the engine to synthesize a word in [locale] to a scratch file (nothing is heard),
     * which makes engines like Google's fetch that voice if it isn't installed yet.
     */
    fun prefetchVoice(locale: Locale) {
        if (_availability.value != Availability.READY) return
        applyVoice(locale, 1f)
        val file = File(cacheDir, "tts-prefetch-${locale.language}.wav")
        tts.synthesizeToFile("halo", Bundle(), file, "prefetch-${locale.language}")
    }

    fun shutdown() {
        session++
        tts.stop()
        tts.shutdown()
    }

    private fun applyVoice(locale: Locale, rate: Float) {
        if (locale != currentLocale) {
            val result = tts.setLanguage(locale)
            _languageMissing.value =
                result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED
            currentLocale = locale
        }
        tts.setSpeechRate(rate)
    }

    private fun parse(utteranceId: String): Pair<Int, Int>? {
        val parts = utteranceId.split(':')
        if (parts.size != 2) return null
        val id = parts[0].toIntOrNull() ?: return null
        val chunk = parts[1].toIntOrNull() ?: return null
        return id to chunk
    }

    companion object {
        /** Keeps every chunk well under [TextToSpeech.getMaxSpeechInputLength] (4000). */
        private const val CHUNK_LIMIT = 3000

        /** Splits `text.substring(from)` at sentence or word breaks; pairs are (offset, text). */
        internal fun chunk(text: String, from: Int, limit: Int = CHUNK_LIMIT): List<Pair<Int, String>> {
            val result = mutableListOf<Pair<Int, String>>()
            var start = from
            while (start < text.length) {
                var end = minOf(start + limit, text.length)
                if (end < text.length) {
                    val window = text.substring(start, end)
                    val sentenceBreak = window.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                    val wordBreak = window.lastIndexOf(' ')
                    val cut = when {
                        sentenceBreak > limit / 2 -> sentenceBreak + 1
                        wordBreak > 0 -> wordBreak + 1
                        else -> window.length
                    }
                    end = start + cut
                }
                val part = text.substring(start, end)
                if (part.isNotBlank()) result += start to part
                start = end
            }
            return result
        }
    }
}
