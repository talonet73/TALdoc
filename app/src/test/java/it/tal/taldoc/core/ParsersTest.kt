package it.tal.taldoc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()

    private fun load(name: String): LoadedDocument {
        val bytes = fixture(name)
        val kind = OfficeParsers.detect(name, null, bytes.copyOf(minOf(bytes.size, 2048)))
        return DocumentParsers.parse(name, kind, bytes)
    }

    private fun assertInvoice(doc: LoadedDocument) {
        val t = doc.fullText
        assertTrue("SSI FACTORY mancante in ${doc.name}: $t", t.contains("SSI FACTORY"))
        assertTrue("Via Roma mancante in ${doc.name}", t.contains("Via Roma 10"))
        assertTrue("Totale mancante in ${doc.name}", t.contains("1.220,00"))
        assertTrue("Consulenza mancante in ${doc.name}", t.contains("Consulenza"))
    }

    @Test fun docx() { val d = load("fattura.docx"); assertEquals("Word", d.kind); assertInvoice(d); assertTrue(d.tables.isNotEmpty()) }
    @Test fun doc() { val d = load("fattura.doc"); assertEquals("Word 97-2003", d.kind); assertInvoice(d); assertTrue(d.notes.isEmpty()) }
    @Test fun odt() { assertInvoice(load("fattura.odt")) }
    @Test fun rtf() { assertInvoice(load("fattura.rtf")) }

    private fun assertBudget(doc: LoadedDocument) {
        assertTrue("tabella mancante in ${doc.name}", doc.tables.isNotEmpty())
        val rows = doc.tables.first().rows
        assertEquals("Voce", rows[0][0])
        assertEquals("Totale", rows.last()[0])
        assertEquals(900.5, Numbers.parseCell(rows.last()[1])!!, 0.001)
        assertEquals(60.5, Numbers.parseCell(rows[2][1])!!, 0.001)
    }

    @Test fun xlsx() { val d = load("budget.xlsx"); assertEquals("Excel", d.kind); assertBudget(d) }
    @Test fun xls() { val d = load("budget.xls"); assertEquals("Excel 97-2003", d.kind); assertBudget(d) }
    @Test fun ods() { assertBudget(load("budget.ods")) }
    @Test fun csv() { assertBudget(load("budget.csv")) }

    @Test fun textEncodingFallback() {
        val bytes = "Perché è così: 10€".toByteArray(charset("windows-1252"))
        assertEquals("Perché è così: 10€", OfficeParsers.decodeText(bytes))
    }
}
