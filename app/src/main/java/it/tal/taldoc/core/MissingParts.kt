package it.tal.taldoc.core

/** Cerca parti mancanti: pagine saltate, numerazioni interrotte, campi lasciati vuoti. */
object MissingParts {

    private val pageMarkRe = Regex("\\b(?:pag(?:ina)?|page|p)\\.?\\s*(\\d{1,4})\\s*(?:di|/|of|su)\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)

    private val sequenceRes = listOf(
        "Articolo" to Regex("^\\s*(?:art\\.?|articolo)\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE),
        "Capitolo" to Regex("^\\s*(?:cap\\.?|capitolo)\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE),
        "Sezione" to Regex("^\\s*(?:sez\\.?|sezione)\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE),
        "Punto numerato" to Regex("^\\s*(\\d{1,3})[.)]\\s+\\p{L}"),
        "Allegato" to Regex("^\\s*allegato\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE),
    )

    private val placeholderRe = Regex("^\\s*([\\p{L}][\\p{L} .'/]{1,40}?)\\s*:?\\s*(?:_{3,}|\\.{4,}|…{2,})\\s*$")
    private val knownFields = listOf(
        "firma", "data", "luogo", "codice fiscale", "partita iva", "p.iva", "iban", "importo", "nome", "cognome",
        "indirizzo", "telefono", "email", "e-mail", "timbro", "data di nascita", "luogo di nascita", "residenza",
    )

    fun findings(doc: LoadedDocument): List<String> {
        val out = mutableListOf<String>()
        // 1. Pagine indicate "pag. x di y"
        val declaredTotals = mutableSetOf<Int>()
        val seenPages = mutableSetOf<Int>()
        doc.pages.forEach { p ->
            pageMarkRe.findAll(p.text).forEach { m ->
                val x = m.groupValues[1].toInt()
                val y = m.groupValues[2].toInt()
                if (y in 1..2000 && x in 1..y) { declaredTotals += y; seenPages += x }
            }
        }
        if (declaredTotals.isNotEmpty()) {
            val total = declaredTotals.max()
            val missing = (1..total).filter { it !in seenPages }
            if (missing.isNotEmpty()) out += "Il documento dichiara $total pagine ma mancano le pagine: ${ranges(missing)}."
            else out += "✓ Numerazione pagine completa (1–$total)."
        }
        // 2. Sequenze numerate
        val lines = doc.pages.flatMap { p -> p.text.lines().map { p.page to it } }
        for ((label, re) in sequenceRes) {
            val nums = lines.mapNotNull { (_, l) -> re.find(l)?.groupValues?.get(1)?.toIntOrNull() }
            val distinct = nums.distinct().sorted()
            if (distinct.size < 3) continue
            val first = distinct.first()
            val last = distinct.last()
            if (last - first > 300) continue
            val missing = (first..last).filter { it !in distinct }
            // numerazione plausibile: pochi buchi rispetto agli elementi presenti
            if (missing.isNotEmpty() && missing.size <= distinct.size) {
                out += "$label: numerazione interrotta, mancano ${ranges(missing)} (presenti $first–$last)."
            }
        }
        // 3. Campi vuoti
        val empty = mutableListOf<String>()
        lines.forEachIndexed { idx, (page, l) ->
            val m = placeholderRe.find(l)
            if (m != null) { empty += "«${m.groupValues[1].trim()}» (pag. $page)"; return@forEachIndexed }
            val t = l.trim()
            if (t.endsWith(":")) {
                val label = t.dropLast(1).trim().lowercase()
                if (label in knownFields) {
                    val next = lines.getOrNull(idx + 1)?.second?.trim().orEmpty()
                    val nextIsLabel = next.isEmpty() || next.endsWith(":") || knownFields.any { next.lowercase().startsWith("$it:") }
                    if (nextIsLabel) empty += "«${t.dropLast(1).trim()}» (pag. $page)"
                }
            }
        }
        if (empty.isNotEmpty()) out += "Campi da compilare lasciati vuoti: ${empty.distinct().take(20).joinToString(", ")}."
        // 4. Pagine senza testo (scansione illeggibile o pagina bianca)
        val blank = doc.pages.filter { it.text.isBlank() }.map { it.page }
        if (blank.isNotEmpty() && doc.pages.size > 1) out += "Pagine senza testo leggibile: ${ranges(blank)}."
        return out
    }

    fun ranges(nums: List<Int>): String {
        if (nums.isEmpty()) return ""
        val sorted = nums.distinct().sorted()
        val parts = mutableListOf<String>()
        var s = sorted[0]
        var prev = s
        for (n in sorted.drop(1) + Int.MIN_VALUE) {
            if (n == prev + 1) { prev = n; continue }
            parts += if (s == prev) "$s" else "$s–$prev"
            s = n; prev = n
        }
        return parts.joinToString(", ")
    }

    fun report(docs: List<LoadedDocument>, compareWith: Pair<LoadedDocument, LoadedDocument>? = null): String {
        if (docs.isEmpty()) return "Nessun documento caricato."
        val sb = StringBuilder("Ricerca di parti mancanti\n\n")
        for (doc in docs) {
            val f = findings(doc)
            sb.append("📄 ${doc.name}\n")
            if (f.isEmpty()) sb.append("   Nessuna parte mancante evidente (pagine, numerazioni e campi compilati).\n")
            else f.forEach { sb.append("   • $it\n") }
            sb.append('\n')
        }
        if (compareWith != null) {
            val (a, b) = compareWith
            val r = DocCompare.compare(a, b)
            sb.append("Confronto ${a.name} → ${b.name}\n")
            if (r.onlyA.isEmpty()) sb.append("   Nessuna parte di A manca in B.\n")
            else {
                sb.append("   Parti di A che mancano in B (${r.onlyA.size}):\n")
                r.onlyA.take(25).forEach { sb.append("   • pag.${it.page}: ${TextUtils.truncate(it.text, 140)}\n") }
            }
            if (r.onlyB.isNotEmpty()) {
                sb.append("   Parti di B che mancano in A (${r.onlyB.size}):\n")
                r.onlyB.take(25).forEach { sb.append("   • pag.${it.page}: ${TextUtils.truncate(it.text, 140)}\n") }
            }
        }
        return sb.toString().trimEnd()
    }
}
