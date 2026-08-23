package id.dotcode.braille.ocr

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ScaffoldTest {
    @Test
    fun `module has no android on the classpath`() {
        val loaded = runCatching { Class.forName("android.graphics.Bitmap") }.isSuccess
        assertEquals(false, loaded, "ocr-core must stay free of Android dependencies")
    }
}
