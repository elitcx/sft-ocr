package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.model.OcrDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional second pass after recognition: sends the page's text to the Gemini API, which
 * fixes recognition errors using the surrounding context, something a word list can't do
 * (it would "fix" subject terms, names, and deliberate typos in an exercise).
 *
 * Only text leaves the device, never the photo. Blocks go up as a JSON array and must come
 * back as an array of the same length, so the document's structure (roles, columns, order)
 * is untouched; only each block's `text` is replaced. Markers are never sent.
 */
class GeminiCorrector {

    data class Outcome(val document: OcrDocument, val changedBlocks: Int, val elapsedMs: Long)

    /** Throws [GeminiException] with a message fit to show the user. */
    suspend fun correct(document: OcrDocument, apiKey: String, model: String): Outcome =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val texts = document.blocks.map { it.text }
            val responseText = postWithRetry(GeminiProtocol.requestBody(texts), apiKey, model)
            val corrected = GeminiProtocol.parseCorrections(responseText, expectedCount = texts.size)
            val (merged, changed) = GeminiProtocol.apply(document, corrected)
            Outcome(merged, changed, System.currentTimeMillis() - started)
        }

    /**
     * Overload (5xx "high demand") and per-model quota (429) errors are common and
     * short-lived, and each model has its own capacity and free-tier quota. So: retry the
     * chosen model with growing pauses on overload, then walk [GeminiProtocol.FALLBACK_MODELS].
     * A model the key can't use (404) is skipped. A bad key fails immediately.
     */
    private suspend fun postWithRetry(body: String, apiKey: String, model: String): String {
        var lastError: GeminiException? = null
        val models = listOf(model) + GeminiProtocol.FALLBACK_MODELS.filter { it != model }
        for ((index, candidate) in models.withIndex()) {
            val pauses = if (index == 0) RETRY_PAUSES_MS else FALLBACK_PAUSES_MS
            for (pauseMs in pauses) {
                delay(pauseMs)
                try {
                    return post(body, apiKey, candidate)
                } catch (e: GeminiException) {
                    lastError = e
                    when (e.code) {
                        in OVERLOAD_CODES -> continue
                        429, 404 -> break
                        else -> throw e
                    }
                }
            }
        }
        throw lastError ?: GeminiException("Gemini sedang sibuk. Coba lagi nanti.")
    }

    private fun post(body: String, apiKey: String, model: String): String {
        val name = URLEncoder.encode(model.trim(), "UTF-8")
        val connection = URL("$ENDPOINT/$name:generateContent").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Header, not query string: keeps the key out of URLs and any logs of them.
            connection.setRequestProperty("x-goog-api-key", apiKey.trim())
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw GeminiException(GeminiProtocol.errorMessage(code, text), code)
            }
            return text
        } catch (e: IOException) {
            throw GeminiException("Tidak bisa menghubungi Gemini. Periksa koneksi internet.")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        val RETRY_PAUSES_MS = listOf(0L, 2_000L, 5_000L)
        val FALLBACK_PAUSES_MS = listOf(0L, 2_000L)
        val OVERLOAD_CODES = setOf(500, 502, 503, 504)
    }
}

/** [code] is the HTTP status when Gemini answered with an error, null for anything else. */
class GeminiException(message: String, val code: Int? = null) : Exception(message)

/** Request/response shapes and the safety checks, kept free of I/O so they can be unit-tested. */
internal object GeminiProtocol {

    const val DEFAULT_MODEL = "gemini-3.5-flash"

    /** Tried in order when the chosen model stays overloaded or out of quota. */
    val FALLBACK_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.6-flash")

    /**
     * A block whose "correction" changes more than this share of its characters is kept as
     * recognized: that is a rewrite, not a fix of misread letters.
     */
    const val MAX_CHANGE_RATIO = 0.35

    val INSTRUCTIONS = """
        You fix text-recognition (OCR) errors in photographed Indonesian school material,
        which may also contain English.

        Input: a JSON array of strings, one per text block, in reading order.
        Output: a JSON array with exactly the same number of strings, in the same order,
        where each string is the corresponding block with recognition errors fixed.

        Fix only errors a text recognizer makes: misread letters (rn/m, cl/d, l/i/1, O/0,
        e/c), digits inside words, missing, doubled or extra letters, and words split or
        joined by mistake. Use the surrounding context to choose the right word.

        Never change a number: years, dates, chapter and page numbers, prices, scores and
        measurements must stay digit for digit, even if they look wrong or out of date.
        Never rephrase, translate, summarize, reorder, add or remove content. Keep units,
        formulas, names, codes, subject terms, capitalization and punctuation as they are
        unless they are clearly misread. Keep typos that are
        clearly intentional, for example in an exercise that asks the reader to find
        mistakes. If you are not sure, leave the text unchanged.
    """.trimIndent()

    fun requestBody(texts: List<String>): String {
        val instructions = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", INSTRUCTIONS)))
        val content = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", JSONArray(texts).toString())))
        val config = JSONObject()
            .put("temperature", 0)
            .put("responseMimeType", "application/json")
            .put("responseSchema", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")))
        return JSONObject()
            .put("systemInstruction", instructions)
            .put("contents", JSONArray().put(content))
            .put("generationConfig", config)
            .toString()
    }

    fun parseCorrections(response: String, expectedCount: Int): List<String> {
        try {
            val candidate = JSONObject(response).optJSONArray("candidates")?.optJSONObject(0)
                ?: throw GeminiException("Gemini tidak memberi jawaban (mungkin diblokir filter keamanan).")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                ?: throw GeminiException(
                    "Gemini tidak memberi jawaban (${candidate.optString("finishReason", "tanpa alasan")}).",
                )
            val text = buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (!part.optBoolean("thought")) append(part.optString("text"))
                }
            }
            val array = JSONArray(text)
            if (array.length() != expectedCount) {
                throw GeminiException("Jawaban Gemini tidak cocok dengan halaman (${array.length()} dari $expectedCount blok).")
            }
            return List(array.length()) { array.getString(it) }
        } catch (e: JSONException) {
            throw GeminiException("Jawaban Gemini tidak bisa dibaca.")
        }
    }

    /** Applies [corrected] block by block; returns the document and how many blocks changed. */
    fun apply(document: OcrDocument, corrected: List<String>): Pair<OcrDocument, Int> {
        var changed = 0
        val blocks = document.blocks.mapIndexed { i, block ->
            val candidate = corrected[i].trim()
            val accept = candidate.isNotEmpty() &&
                candidate != block.text &&
                changeRatio(block.text, candidate) <= MAX_CHANGE_RATIO &&
                numbers(candidate) == numbers(block.text)
            if (accept) {
                changed++
                block.copy(text = candidate)
            } else {
                block
            }
        }
        return document.copy(blocks = blocks) to changed
    }

    /**
     * The numbers in [text], order-insensitive. A correction must keep this unchanged: a
     * model "fixing" 2026 to 2024 is worse than any typo. A word that is really a misread
     * number ("2O26", "l5") counts as that number, and a price's "@" read as a leading 0
     * ("010.000" for "@10.000") counts as that 0, so fixing those is still allowed.
     */
    fun numbers(text: String): Map<String, Int> =
        TOKEN.findAll(AT_BEFORE_DIGIT.replace(text, "0"))
            .map { it.value }
            .filter { token -> token.any { it.isDigit() } }
            .map { token -> token.map { DIGIT_LOOKALIKES[it] ?: it }.joinToString("") }
            .filter { token -> token.all { it.isDigit() } }
            .groupingBy { it }
            .eachCount()

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val AT_BEFORE_DIGIT = Regex("@(?=\\d)")
    private val DIGIT_LOOKALIKES = mapOf('O' to '0', 'o' to '0', 'l' to '1', 'I' to '1', 'S' to '5', 'B' to '8')

    fun errorMessage(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val message = detail?.optString("message").orEmpty()
        return when {
            message.contains("API key", ignoreCase = true) -> "Kunci API Gemini tidak valid."
            code == 429 -> "Kuota kunci API Gemini habis. Coba lagi nanti."
            code == 503 || code == 500 -> "Server Gemini sedang sibuk. Coba lagi sebentar lagi."
            code == 404 -> "Model Gemini tidak ditemukan. Periksa nama model di Pengaturan."
            else -> "Gemini gagal ($code)" + if (message.isNotEmpty()) ": $message" else "."
        }
    }

    /** Levenshtein distance divided by the longer length: 0 = identical, 1 = unrelated. */
    fun changeRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j - 1] + cost, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
