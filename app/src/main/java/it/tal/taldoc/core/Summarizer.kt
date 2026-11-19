package it.tal.taldoc.core

import kotlin.math.ln

/** Riassunto estrattivo offline + dati chiave (importi, date, contatti, nomi). */
object Summarizer {

    fun summarize(doc: LoadedDocument, focus: String = "", maxSentences: Int = 7): String {
        val text = doc.fullText
        if (text.isBlank()) return "📄 ${doc.name}\nNessun testo riconosciuto."
        val sentences = TextUtils.sentences(text).filter { it.length in 25..400 }
        val sb = StringBuilder("📄 ${doc.name} — ${doc.kind}, ${doc.pages.size} pag., ${wordCount(text)} parole\n")
        if (sentences.isEmpty()) {
            sb.append("\n").append(TextUtils.truncate(TextUtils.normalizeLine(text), 900)).append('\n')
        } else {
            val freq = HashMap<String, Int>()
            sentences.forEach { s -> TextUtils.keywords(s).map(TextUtils::stem).forEach { freq[it] = (freq[it] ?: 0) + 1 } }
            val focusTerms = TextUtils.keywords(focus).map(TextUtils::stem).toSet()
            val maxF = (freq.values.maxOrNull() ?: 1).toDouble()
            val scored = sentences.mapIndexed { i, s ->
                val terms = TextUtils.keywords(s).map(TextUtils::stem)
                if (terms.isEmpty()) return@mapIndexed Triple(i, s, 0.0)
                var score = terms.sumOf { (freq[it] ?: 0) / maxF } / ln(2.0 + terms.size)
                if (i < 3) score *= 1.4
                if (Numbers.monetary(s).isNotEmpty()) score *= 1.15
                if (focusTerms.isNotEmpty()) score *= 1.0 + 1.5 * terms.count { it in focusTerms }
                Triple(i, s, score)
            }
            val picked = scored.sortedByDescending { it.third }
                .fold(mutableListOf<Triple<Int, String, Double>>()) { acc, t ->
                    if (acc.size < maxSentences && acc.none { TextUtils.tokenSimilarity(it.second, t.second) > 0.6 }) acc += t
                    acc
                }
                .sortedBy { it.first }
            sb.append("\nIn sintesi:\n")
            picked.forEach { sb.append("• ").append(it.second).append('\n') }
        }
        val facts = keyFacts(doc)
        if (facts.isNotEmpty()) {
            sb.append("\nDati chiave:\n")
            facts.forEach { sb.append("• ").append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    fun keyFacts(doc: LoadedDocument): List<String> {
        val text = doc.fullText
        val out = mutableListOf<String>()
        val totals = text.lines().filter { Regex("(?i)\\b(totale|netto|importo|imponibile|saldo|da pagare)\\b").containsMatchIn(it) && Numbers.monetary(it).isNotEmpty() }
            .map { TextUtils.normalizeLine(it) }.distinct().take(4)
        if (totals.isNotEmpty()) out += "Importi: " + totals.joinToString(" · ")
        val dates = Regex("\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b").findAll(text).map { it.value }.distinct().take(6).toList()
        if (dates.isNotEmpty()) out += "Date: " + dates.joinToString(", ")
        val emails = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+").findAll(text).map { it.value }.distinct().take(4).toList()
        if (emails.isNotEmpty()) out += "Email: " + emails.joinToString(", ")
        val ent = TextUtils.entities(text).filter { it.length in 4..40 }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(6).map { it.key }
        if (ent.isNotEmpty()) out += "Nomi ricorrenti: " + ent.joinToString(", ")
        return out
    }

    private fun wordCount(s: String) = TextUtils.tokens(s).size

    fun report(docs: List<LoadedDocument>, focus: String = ""): String =
        if (docs.isEmpty()) "Nessun documento caricato." else docs.joinToString("\n\n") { summarize(it, focus) }
}
