package it.tal.taldoc.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/** Numero trovato in una riga di testo. */
data class Amount(
    val value: Double,
    val raw: String,
    val start: Int,
    val end: Int,
    val hasDecimals: Boolean,
    val currency: Boolean,
    val percent: Boolean,
) {
    /** Probabile importo monetario (ha decimali o simbolo di valuta). */
    val monetary: Boolean get() = !percent && (hasDecimals || currency)
}

object Numbers {

    private val numberRe = Regex(
        "(€|EUR|eur)?\\s?(-?\\d{1,3}(?:[.']\\d{3})+(?:[.,]\\d{1,2})?|-?\\d+(?:[.,]\\d{1,3})?)(\\s?(?:€|EUR|euro))?(\\s?%)?"
    )
    private val dateRe = Regex("\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b")
    private val timeRe = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b")

    /** Converte una stringa numerica italiana o inglese in Double. */
    fun parse(raw: String): Double? {
        var s = raw.trim().replace("€", "").replace("EUR", "", true).replace("euro", "", true)
            .replace(" ", "").replace(" ", "").replace("'", "").replace("%", "")
        if (s.isEmpty()) return null
        val neg = s.startsWith("-") || (s.startsWith("(") && s.endsWith(")"))
        s = s.trim('-', '(', ')', '+')
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val normalized = when {
            lastDot >= 0 && lastComma >= 0 -> {
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            }
            lastComma >= 0 -> {
                val decimals = s.length - lastComma - 1
                val groups = s.count { it == ',' }
                if (decimals == 3 && (groups > 1 || s.length > 5)) s.replace(",", "")
                else if (groups > 1) s.replace(",", "") else s.replace(',', '.')
            }
            lastDot >= 0 -> {
                val decimals = s.length - lastDot - 1
                val groups = s.count { it == '.' }
                if (groups > 1 || (decimals == 3 && s.indexOf('.') <= 3 && s.length > 4)) s.replace(".", "") else s
            }
            else -> s
        }
        val v = normalized.toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    /** Valore di una cella di foglio di calcolo (formato "1234.5" o italiano). */
    fun parseCell(raw: String): Double? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (Regex("-?\\d+(\\.\\d+)?([eE]-?\\d+)?").matches(t)) return t.toDoubleOrNull()
        if (!Regex("[€(]?\\s?-?[\\d.,' ]+\\s?(€|EUR)?\\)?").matches(t)) return null
        return parse(t)
    }

    /** Tutti i numeri della riga, esclusi date, orari e parti di codici alfanumerici. */
    fun find(line: String): List<Amount> {
        val masked = StringBuilder(line)
        for (re in listOf(dateRe, timeRe)) re.findAll(line).forEach { m ->
            for (i in m.range) masked.setCharAt(i, '#')
        }
        val text = masked.toString()
        val out = mutableListOf<Amount>()
        for (m in numberRe.findAll(text)) {
            val numGroup = m.groups[2] ?: continue
            val s = numGroup.range.first
            val e = numGroup.range.last + 1
            // scarta numeri attaccati a lettere (codici tipo AB123, 3x, IT60X...)
            val before = if (s > 0) text[s - 1] else ' '
            val after = if (e < text.length) text[e] else ' '
            if (before.isLetter() || after.isLetter() && !text.startsWith("EUR", e) && !text.startsWith("euro", e, true)) continue
            if (before == '#' || after == '#' || before == '/' || after == '/') continue
            val raw = numGroup.value
            val v = parse(raw) ?: continue
            val trimmed = raw.trim()
            val hasDec = Regex("[.,]\\d{1,2}$").containsMatchIn(trimmed) &&
                !(Regex("^-?\\d{1,3}([.,]\\d{3})+$").matches(trimmed))
            val currency = m.groups[1] != null || m.groups[3] != null
            val percent = m.groups[4] != null
            out += Amount(v, m.value.trim(), s, e, hasDec, currency, percent)
        }
        return out
    }

    fun monetary(line: String): List<Amount> = find(line).filter { it.monetary }

    fun close(a: Double, b: Double, tol: Double = 0.011): Boolean = abs(a - b) <= tol

    fun round2(v: Double): Double = BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toDouble()

    private val itFormat = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.ITALIAN))
    fun format(v: Double): String = itFormat.format(v)
}
