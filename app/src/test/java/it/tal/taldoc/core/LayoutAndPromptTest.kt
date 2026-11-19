package it.tal.taldoc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutAndPromptTest {

    @Test fun ocrRowsAreMerged() {
        // l'OCR restituisce prima la colonna sinistra poi quella destra
        val lines = listOf(
            OcrLine("Pane", 10, 100, 80, 120),
            OcrLine("Latte", 10, 130, 90, 150),
            OcrLine("TOTALE", 10, 200, 120, 222),
            OcrLine("2,50", 300, 101, 350, 121),
            OcrLine("1,30", 300, 131, 350, 151),
            OcrLine("3,80", 300, 199, 350, 221),
        )
        val text = OcrLayout.rebuild(lines)
        val rows = text.lines().filter { it.isNotBlank() }
        assertEquals(listOf("Pane  2,50", "Latte  1,30", "TOTALE  3,80"), rows)
        val doc = LoadedDocument("s", "Immagine", listOf(PageText("s", 1, text)))
        assertTrue(CalcChecker.check(doc).all { it.ok })
    }

    @Test fun clarificationParsing() {
        assertEquals("Quale totale intendi?", Prompts.parseClarification("CHIARIMENTO: Quale totale intendi?"))
        assertNull(Prompts.parseClarification("Il totale è 100 €"))
    }

    @Test fun contextRespectsBudget() {
        val ps = (1..50).map { Passage("d", it, "x".repeat(500), 1.0) }
        assertTrue(Prompts.context(ps, 3000).length <= 3000)
    }

    @Test fun chunking() {
        val text = (1..40).joinToString("\n\n") { "Paragrafo $it " + "parola ".repeat(50) }
        val c = Prompts.chunks(text, 2000, 4)
        assertEquals(4, c.size)
        assertTrue(c.all { it.length <= 2400 })
    }
}
