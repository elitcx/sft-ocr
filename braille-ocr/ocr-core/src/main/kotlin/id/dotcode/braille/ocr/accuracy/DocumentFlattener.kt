package id.dotcode.braille.ocr.accuracy

import id.dotcode.braille.ocr.model.OcrDocument

/**
 * The single definition of "this document's text, the way a reader would encounter it" used
 * for accuracy scoring: reading order, one block per line, a block's marker restored as a
 * prefix. This used to be a private function duplicated (in spirit) between the accuracy
 * harness and anything else that needed the pipeline's raw text — kept here instead so a
 * ground-truth bootstrap tool and the accuracy harness score against exactly the same string.
 *
 * This is deliberately not [id.dotcode.braille.ocr.model.OcrDocument]'s only textual
 * rendering: a user-facing plain-text export may reflow indentation and spacing differently.
 * This one exists purely to be diffed against a human transcript.
 */
object DocumentFlattener {
    fun flatten(document: OcrDocument): String =
        document.blocks.joinToString("\n") { block ->
            listOfNotNull(block.marker?.takeIf { it.isNotBlank() }, block.text)
                .joinToString(" ")
                .trim()
        }
}
