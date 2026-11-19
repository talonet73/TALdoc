package it.tal.taldoc.core

import kotlin.math.abs

/** Esito di un controllo aritmetico. */
data class CalcFinding(val ok: Boolean, val document: String, val where: String, val message: String)

/**
 * Verifica i conti nei documenti: totali e subtotali, righe quantità × prezzo,
 * IVA sull'imponibile, totali di colonna nelle tabelle.
 */
object CalcChecker {

    private val totalRe = Regex(
        "\\b(totale|totali|subtotale|sub-totale|sub totale|tot\\.?|somma|importo complessivo|imponibile|" +
            "netto a pagare|da pagare|totale a pagare|total|subtotal|amount due|saldo)\\b",
        RegexOption.IGNORE_CASE
    )
    private val vatRe = Regex("\\b(iva|i\\.v\\.a\\.?|vat)\\b", RegexOption.IGNORE_CASE)
    private val taxableRe = Regex("\\bimponibile\\b", RegexOption.IGNORE_CASE)

    private data class Row(val page: Int, val text: String, val amounts: List<Amount>, val isTotal: Boolean)

    fun check(doc: LoadedDocument): List<CalcFinding> {
        val findings = mutableListOf<CalcFinding>()
        val rows = doc.pages.flatMap { p ->
            p.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                Row(p.page, line, Numbers.find(line), totalRe.containsMatchIn(line))
            }
        }
        findings += checkTotals(doc.name, rows)
        findings += checkLineProducts(doc.name, rows)
        findings += checkVat(doc.name, rows)
        doc.tables.forEach { findings += checkTable(doc.name, it) }
        return findings.distinctBy { it.where + it.message }
    }

    private fun checkTotals(docName: String, rows: List<Row>): List<CalcFinding> {
        val out = mutableListOf<CalcFinding>()
        var windowStart = 0
        val previousTotals = mutableListOf<Double>()
        rows.forEachIndexed { i, row ->
            if (!row.isTotal) return@forEachIndexed
            val declared = row.amounts.lastOrNull { it.monetary } ?: return@forEachIndexed
            val t = declared.value
            val values = rows.subList(windowStart, i)
                .filter { !it.isTotal }
                .mapNotNull { r -> r.amounts.lastOrNull { it.monetary }?.value }
            val where = "pag. ${row.page}: «${TextUtils.truncate(row.text, 70)}»"
            val label = Regex("^[^0-9€]*").find(row.text)?.value?.trim()?.trimEnd(':', '.', ' ')?.ifEmpty { null } ?: "Totale"
            when {
                values.size >= 2 && Numbers.close(values.sum(), t) ->
                    out += CalcFinding(true, docName, where, "$label ${Numbers.format(t)} = somma di ${values.size} voci ✓")
                values.size >= 2 && suffixMatches(values, t) ->
                    out += CalcFinding(true, docName, where, "$label ${Numbers.format(t)} = somma delle ultime voci ✓")
                previousTotals.isNotEmpty() && values.isNotEmpty() && Numbers.close(previousTotals.last() + values.sum(), t) ->
                    out += CalcFinding(
                        true, docName, where,
                        "$label ${Numbers.format(t)} = ${Numbers.format(previousTotals.last())} + ${values.joinToString(" + ") { Numbers.format(it) }} ✓"
                    )
                pairMatches(previousTotals, t) != null -> {
                    val (a, b) = pairMatches(previousTotals, t)!!
                    out += CalcFinding(true, docName, where, "$label ${Numbers.format(t)} = ${Numbers.format(a)} + ${Numbers.format(b)} ✓")
                }
                values.size >= 2 && plausibleList(values, t) -> {
                    val s = Numbers.round2(values.sum())
                    out += CalcFinding(
                        false, docName, where,
                        "$label dichiarato ${Numbers.format(t)}, ma la somma delle ${values.size} voci sopra è ${Numbers.format(s)} " +
                            "(differenza ${Numbers.format(Numbers.round2(t - s))})"
                    )
                }
            }
            previousTotals += t
            windowStart = i + 1
        }
        return out
    }

    private fun suffixMatches(values: List<Double>, t: Double): Boolean {
        var acc = 0.0
        for (k in values.indices.reversed()) {
            acc += values[k]
            if (values.size - k >= 2 && Numbers.close(acc, t)) return true
        }
        return false
    }

    private fun pairMatches(totals: List<Double>, t: Double): Pair<Double, Double>? {
        val last = totals.takeLast(4)
        for (i in last.indices) for (j in i + 1 until last.size) {
            if (Numbers.close(last[i] + last[j], t)) return last[i] to last[j]
        }
        return null
    }

    /** Evita falsi allarmi: le voci devono essere comparabili con il totale. */
    private fun plausibleList(values: List<Double>, t: Double): Boolean {
        if (t <= 0) return false
        val s = values.sum()
        if (values.any { it > t * 1.01 }) return false
        return s in (t * 0.5)..(t * 1.5)
    }

    private fun checkLineProducts(docName: String, rows: List<Row>): List<CalcFinding> {
        val out = mutableListOf<CalcFinding>()
        for (row in rows) {
            if (row.isTotal) continue
            val nums = row.amounts.filter { !it.percent }
            if (nums.size != 3) continue
            val result = nums[2]
            if (!result.monetary) continue
            val a = nums[0].value
            val b = nums[1].value
            if (a <= 0 || b <= 0 || a > 100000) continue
            val product = a * b
            val where = "pag. ${row.page}: «${TextUtils.truncate(row.text, 70)}»"
            if (Numbers.close(product, result.value, 0.011)) {
                out += CalcFinding(true, docName, where, "${nums[0].raw} × ${nums[1].raw} = ${Numbers.format(result.value)} ✓")
            } else if (abs(product - result.value) / maxOf(result.value, 0.01) < 0.15 && nums[0].value == Math.floor(nums[0].value)) {
                out += CalcFinding(
                    false, docName, where,
                    "${nums[0].raw} × ${nums[1].raw} = ${Numbers.format(Numbers.round2(product))}, ma nella riga è indicato ${Numbers.format(result.value)}"
                )
            }
        }
        return out
    }

    private fun checkVat(docName: String, rows: List<Row>): List<CalcFinding> {
        val out = mutableListOf<CalcFinding>()
        var taxable: Double? = null
        for (row in rows) {
            if (taxableRe.containsMatchIn(row.text) && !vatRe.containsMatchIn(row.text)) {
                taxable = row.amounts.lastOrNull { it.monetary }?.value ?: taxable
                continue
            }
            if (!vatRe.containsMatchIn(row.text)) continue
            val pct = row.amounts.firstOrNull { it.percent }?.value
                ?: Regex("(\\d{1,2})\\s?%").find(row.text)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: continue
            val money = row.amounts.filter { it.monetary }
            val vat = money.lastOrNull()?.value ?: continue
            val base = if (money.size >= 2) money[money.size - 2].value else taxable ?: continue
            val expected = Numbers.round2(base * pct / 100.0)
            val where = "pag. ${row.page}: «${TextUtils.truncate(row.text, 70)}»"
            if (Numbers.close(expected, vat, 0.021)) {
                out += CalcFinding(true, docName, where, "IVA ${pct.toInt()}% di ${Numbers.format(base)} = ${Numbers.format(vat)} ✓")
            } else if (vat < base) {
                out += CalcFinding(
                    false, docName, where,
                    "IVA ${pct.toInt()}% di ${Numbers.format(base)} dovrebbe essere ${Numbers.format(expected)}, indicato ${Numbers.format(vat)}"
                )
            }
        }
        return out
    }

    fun checkTable(docName: String, table: DocTable): List<CalcFinding> {
        val out = mutableListOf<CalcFinding>()
        var start = 0
        table.rows.forEachIndexed { r, row ->
            val isTotal = row.any { totalRe.containsMatchIn(it) && Numbers.parseCell(it) == null }
            if (!isTotal) return@forEachIndexed
            row.forEachIndexed { c, cell ->
                val declared = Numbers.parseCell(cell) ?: return@forEachIndexed
                val values = table.rows.subList(start, r).mapNotNull { it.getOrNull(c)?.let(Numbers::parseCell) }
                if (values.size < 2) return@forEachIndexed
                val sum = Numbers.round2(values.sum())
                val where = "${table.name}, riga ${r + 1}, colonna ${columnName(c)}"
                out += if (Numbers.close(sum, declared, 0.011)) {
                    CalcFinding(true, docName, where, "Totale ${Numbers.format(declared)} = somma di ${values.size} celle ✓")
                } else {
                    CalcFinding(
                        false, docName, where,
                        "Totale ${Numbers.format(declared)} ma la somma delle ${values.size} celle sopra è ${Numbers.format(sum)} " +
                            "(differenza ${Numbers.format(Numbers.round2(declared - sum))})"
                    )
                }
            }
            start = r + 1
        }
        return out
    }

    fun columnName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + i % 26)
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    fun report(docs: List<LoadedDocument>): String {
        if (docs.isEmpty()) return "Nessun documento caricato."
        val sb = StringBuilder()
        var totalErrors = 0
        var totalOk = 0
        for (doc in docs) {
            val f = check(doc)
            val errors = f.filter { !it.ok }
            val oks = f.filter { it.ok }
            totalErrors += errors.size
            totalOk += oks.size
            sb.append("📄 ${doc.name}\n")
            if (f.isEmpty()) {
                sb.append("   Nessun totale o calcolo riconoscibile.\n\n")
                continue
            }
            if (errors.isEmpty()) sb.append("   ✅ Tutti i ${oks.size} controlli tornano.\n")
            else {
                sb.append("   ❌ ${errors.size} conti NON tornano:\n")
                errors.forEach { sb.append("   • ${it.message}\n     (${it.where})\n") }
            }
            if (oks.isNotEmpty() && errors.isNotEmpty()) sb.append("   ✅ ${oks.size} controlli corretti.\n")
            if (oks.isNotEmpty()) {
                sb.append("   Dettaglio verifiche corrette:\n")
                oks.take(8).forEach { sb.append("   · ${it.message}\n") }
                if (oks.size > 8) sb.append("   · … e altre ${oks.size - 8}\n")
            }
            sb.append('\n')
        }
        val head = when {
            totalErrors == 0 && totalOk == 0 -> "Non ho trovato totali o calcoli da verificare.\n" +
                "Suggerimento: funziona meglio con fatture, scontrini, preventivi, buste paga e fogli Excel con righe «Totale».\n\n"
            totalErrors == 0 -> "Verifica conti: tutto torna ($totalOk controlli).\n\n"
            else -> "Verifica conti: $totalErrors problemi trovati, $totalOk controlli corretti.\n\n"
        }
        return head + sb.toString().trimEnd()
    }
}
