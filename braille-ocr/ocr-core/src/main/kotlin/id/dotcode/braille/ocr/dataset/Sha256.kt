package id.dotcode.braille.ocr.dataset

import java.security.MessageDigest

/**
 * Content hash for a source image, so a labelled sample stays identifiable after the file
 * it came from is renamed, re-exported, or re-compressed at the same bytes (a copy). Pure
 * `java.security` — no Android dependency, safe for `:ocr-core`.
 */
object Sha256 {
    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (b in digest) builder.append(HEX_CHARS[(b.toInt() shr 4) and 0xF]).append(HEX_CHARS[b.toInt() and 0xF])
        return builder.toString()
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
