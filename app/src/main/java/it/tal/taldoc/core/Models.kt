package it.tal.taldoc.core

/** Testo di una pagina (o di un foglio, per i fogli di calcolo). */
data class PageText(val document: String, val page: Int, val text: String)

/** Tabella estratta (Excel, CSV, tabelle Word). */
data class DocTable(val name: String, val rows: List<List<String>>)

/** Documento caricato nella sessione. */
data class LoadedDocument(
    val name: String,
    val kind: String,
    val pages: List<PageText>,
    val tables: List<DocTable> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val fullText: String get() = pages.joinToString("\n\n") { it.text }
    val charCount: Int get() = pages.sumOf { it.text.length }
}

enum class Mode(val label: String, val needsText: Boolean) {
    QUESTION("Domanda", true),
    SUMMARY("Riassunto", false),
    TRANSCRIPTION("Trascrizione", false),
    SEARCH("Cerca", true),
    COMPARE("Confronta", false),
    MISSING("Parti mancanti", false),
    CALC("Verifica conti", false),
}

/** Opzione proposta quando serve un chiarimento. */
data class ClarifyOption(val label: String, val query: String, val mode: Mode? = null)

sealed class EngineResult {
    data class Answer(val text: String, val sources: List<String> = emptyList()) : EngineResult()
    data class Clarify(val question: String, val options: List<ClarifyOption>, val original: String) : EngineResult()
}

/** Passaggio di testo recuperato per una domanda. */
data class Passage(val document: String, val page: Int, val text: String, val score: Double) {
    val ref: String get() = "$document · pag. $page"
}
