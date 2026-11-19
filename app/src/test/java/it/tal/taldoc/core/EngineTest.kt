package it.tal.taldoc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    private fun doc(name: String, vararg pages: String) =
        LoadedDocument(name, "Testo", pages.mapIndexed { i, t -> PageText(name, i + 1, t.trimIndent()) })

    private val invoice = doc(
        "fattura.pdf",
        """
        FATTURA N. 125 del 15/03/2026
        SSI FACTORY S.r.l.
        Via Roma 10, 00184 Roma
        Email: info@ssifactory.it
        Consulenza 2 150,00 300,00
        Sviluppo app 1 700,00 700,00
        Imponibile 1.000,00
        IVA 22% 220,00
        Totale fattura 1.220,00
        Pagina 1 di 2
        """
    )

    @Test fun numbersParsing() {
        assertEquals(1234.56, Numbers.parse("1.234,56")!!, 0.0001)
        assertEquals(1234.56, Numbers.parse("1,234.56")!!, 0.0001)
        assertEquals(1000.0, Numbers.parse("1.000")!!, 0.0001)
        assertEquals(12.5, Numbers.parse("12,5")!!, 0.0001)
        assertEquals(-7.0, Numbers.parse("-7")!!, 0.0001)
        val found = Numbers.find("Data 15/03/2026 ore 10:30 codice AB123 importo € 45,90")
        assertEquals(listOf(45.9), found.map { it.value })
        assertTrue(found[0].monetary)
    }

    @Test fun calcOkInvoice() {
        val f = CalcChecker.check(invoice)
        assertTrue(f.toString(), f.none { !it.ok })
        assertTrue(f.toString(), f.any { it.ok && it.message.contains("1.220,00") })
        assertTrue(f.toString(), f.any { it.ok && it.message.startsWith("IVA") })
    }

    @Test fun calcDetectsWrongTotal() {
        val bad = doc("scontrino.jpg", """
            Pane 2,50
            Latte 1,30
            Caffè 4,20
            TOTALE 9,00
        """)
        val f = CalcChecker.check(bad)
        val err = f.filter { !it.ok }
        assertEquals(f.toString(), 1, err.size)
        assertTrue(err[0].message.contains("8,00"))
    }

    @Test fun calcDetectsWrongRowProduct() {
        val bad = doc("preventivo", "Pannelli 3 12,50 38,00\nTotale 38,00")
        assertTrue(CalcChecker.check(bad).any { !it.ok && it.message.contains("37,50") })
    }

    @Test fun calcTable() {
        val t = DocTable("Foglio1", listOf(listOf("Voce", "Gen"), listOf("A", "10"), listOf("B", "20.5"), listOf("Totale", "31")))
        val f = CalcChecker.checkTable("x.xlsx", t)
        assertEquals(1, f.size)
        assertFalse(f[0].ok)
        assertTrue(f[0].message.contains("30,50"))
    }

    @Test fun compareFindsChanges() {
        val a = doc("A", "Contratto di affitto\nCanone mensile 800,00 euro\nDurata 4 anni\nArt. 5 Recesso con preavviso di 6 mesi")
        val b = doc("B", "Contratto di affitto\nCanone mensile 850,00 euro\nDurata 4 anni")
        val r = DocCompare.compare(a, b)
        assertEquals(1, r.changed.size)
        assertEquals(1, r.onlyA.size)
        assertTrue(r.onlyA[0].text.contains("Recesso"))
        assertTrue(r.valueChanges.toString(), r.valueChanges.any { it.contains("800") && it.contains("850") })
        assertTrue(DocCompare.report(a, b).contains("MANCANTI IN B"))
    }

    @Test fun compareIdentical() {
        val a = doc("A", "Riga uno\nRiga due")
        val b = doc("B", "riga  uno\nRiga due.")
        assertTrue(DocCompare.report(a, b).contains("stesso contenuto"))
    }

    @Test fun missingParts() {
        val d = LoadedDocument("contratto", "PDF", listOf(
            PageText("contratto", 1, "Art. 1 Oggetto\nArt. 2 Durata\nPagina 1 di 3"),
            PageText("contratto", 2, "Art. 4 Prezzo\nFirma: ____________\nPagina 3 di 3"),
        ))
        val f = MissingParts.findings(d).joinToString("\n")
        assertTrue(f, f.contains("mancano le pagine: 2"))
        assertTrue(f, f.contains("mancano 3"))
        assertTrue(f, f.contains("Firma"))
    }

    @Test fun questionAddress() {
        val r = QueryEngine(listOf(invoice)).answer("dammi l'indirizzo di SSI FACTORY")
        assertTrue(r.toString(), r is EngineResult.Answer)
        assertTrue((r as EngineResult.Answer).text.contains("Via Roma 10"))
    }

    @Test fun questionEmail() {
        val r = QueryEngine(listOf(invoice)).answer("qual è la mail?") as EngineResult.Answer
        assertTrue(r.text.contains("info@ssifactory.it"))
    }

    @Test fun questionTotalSpecific() {
        val r = QueryEngine(listOf(invoice)).answer("qual è il totale fattura?")
        assertTrue(r.toString(), r is EngineResult.Answer && r.text.contains("1.220,00"))
    }

    @Test fun ambiguousAmountAsksClarification() {
        val r = QueryEngine(listOf(invoice)).answer("quanto è l'importo?")
        assertTrue(r.toString(), r is EngineResult.Clarify)
    }

    @Test fun vagueQuestionAsksClarification() {
        val e = QueryEngine(listOf(invoice))
        assertNotNull(e.clarification("dimmi tutto"))
        assertNotNull(e.clarification(""))
        assertNull(e.clarification("qual è la data della fattura"))
    }

    @Test fun unknownWordSuggestsOcrVariant() {
        val e = QueryEngine(listOf(invoice))
        val c = e.clarification("cosa dice su Consulnza")
        assertTrue(c.toString(), c == null || c.options.any { it.label.contains("consulenza") })
        val missing = e.clarification("parla di ippopotami")
        assertNotNull(missing)
    }

    @Test fun codeLookup() {
        val d = doc("cedolino", "Codice 8002 Indennità trasferta 120,00\nCodice 1000 Paga base 1.500,00")
        val r = QueryEngine(listOf(d)).answer("cosa dice il codice 8002") as EngineResult.Answer
        assertTrue(r.text.contains("Indennità trasferta"))
        assertFalse(r.text.contains("Paga base"))
    }

    @Test fun intents() {
        val e = QueryEngine(listOf(invoice))
        assertEquals(Intent.ADDRESS, e.detectIntent("dove ha sede l'azienda"))
        assertEquals(Intent.AMOUNT, e.detectIntent("qual è la paga base"))
        assertEquals(Intent.DATE, e.detectIntent("quando scade"))
        assertEquals(Intent.IBAN, e.detectIntent("dammi l'IBAN"))
    }

    @Test fun summary() {
        val s = Summarizer.summarize(invoice)
        assertTrue(s, s.contains("Dati chiave"))
        assertTrue(s, s.contains("15/03/2026"))
    }

    @Test fun search() {
        val e = QueryEngine(listOf(invoice))
        assertTrue(e.search("via roma").contains("Trovate 1"))
        assertTrue(e.search("Consulnza").contains("consulenza"))
    }

    @Test fun ranges() { assertEquals("2, 5–7", MissingParts.ranges(listOf(7, 2, 5, 6))) }
}
