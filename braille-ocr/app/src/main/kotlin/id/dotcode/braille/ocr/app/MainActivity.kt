package id.dotcode.braille.ocr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 draws edge-to-edge unconditionally; this just opts into transparent
        // system bar backgrounds so each screen's WindowInsets padding is the only thing
        // standing between content and the status/navigation bars. AppRoot sets the bar
        // icon colours per screen (dark screens get light icons).
        enableEdgeToEdge()
        setContent {
            BrailleTheme {
                val model: OcrViewModel = viewModel()
                AppRoot(model)
            }
        }
    }
}
