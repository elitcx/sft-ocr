package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class MarkerParserTest {
    @Test
    fun `numeric marker with a period`() {
        val parsed = MarkerParser.parse("1. Sebutkan tiga contoh sumber daya alam.")
        assertEquals("1.", parsed?.marker)
        assertEquals(MarkerKind.NUMERIC, parsed?.kind)
        assertEquals("Sebutkan tiga contoh sumber daya alam.", parsed?.remainder)
    }

    @Test
    fun `numeric marker with a parenthesis`() {
        val parsed = MarkerParser.parse("12) Jelaskan proses fotosintesis")
        assertEquals("12)", parsed?.marker)
        assertEquals(MarkerKind.NUMERIC, parsed?.kind)
        assertEquals("Jelaskan proses fotosintesis", parsed?.remainder)
    }

    @Test
    fun `alpha marker`() {
        val parsed = MarkerParser.parse("a. Jakarta")
        assertEquals("a.", parsed?.marker)
        assertEquals(MarkerKind.ALPHA, parsed?.kind)
        assertEquals("Jakarta", parsed?.remainder)
    }

    @Test
    fun `roman marker is preferred over alpha`() {
        val parsed = MarkerParser.parse("iii. Bagian ketiga")
        assertEquals("iii.", parsed?.marker)
        assertEquals(MarkerKind.ROMAN, parsed?.kind)
    }

    @Test
    fun `bullet marker`() {
        val parsed = MarkerParser.parse("• Air bersih")
        assertEquals("•", parsed?.marker)
        assertEquals(MarkerKind.BULLET, parsed?.kind)
        assertEquals("Air bersih", parsed?.remainder)
    }

    @Test
    fun `plain prose has no marker`() {
        assertNull(MarkerParser.parse("Sumber daya alam adalah kekayaan alam."))
    }

    @Test
    fun `a decimal number is not a marker`() {
        assertNull(MarkerParser.parse("3.14 adalah nilai pi"))
    }

    @Test
    fun `a marker with no following text is not a marker`() {
        assertNull(MarkerParser.parse("1."))
    }

    @Test
    fun `an over long number is not a marker`() {
        assertNull(MarkerParser.parse("2024. Tahun itu penting"))
    }

    @Test
    fun `a numeric marker with no space after the period is still recognized`() {
        // Real ML Kit output from a photographed worksheet: the space after the marker
        // punctuation is routinely dropped. The character right after the period is a
        // LETTER here, which is what distinguishes this from a decimal number.
        val parsed = MarkerParser.parse(
            "1.Providing free nutritious meals could support students' learning indirectly",
        )
        assertEquals("1.", parsed?.marker)
        assertEquals(MarkerKind.NUMERIC, parsed?.kind)
        assertEquals(
            "Providing free nutritious meals could support students' learning indirectly",
            parsed?.remainder,
        )
    }

    @Test
    fun `a decimal number with no leading space is still not a marker`() {
        // The no-space allowance is gated on the next character being a LETTER
        // specifically so this stays null: '1' right after the period is a digit, not
        // a letter, so the mandatory-space branch is the only one in play and it fails
        // on the missing space - exactly as it always did.
        assertNull(MarkerParser.parse("3.14adalah nilai pi"))
    }
}
