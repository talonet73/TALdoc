package it.tal.taldoc.core

/** Riga riconosciuta dall'OCR con il suo riquadro. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Double get() = (top + bottom) / 2.0
}

/**
 * Ricostruisce l'ordine di lettura riga per riga: l'OCR restituisce blocchi separati
 * (es. descrizione a sinistra, importo a destra); qui vengono riuniti sulla stessa riga,
 * così tabelle, scontrini e fatture mantengono "voce ... importo" insieme.
 */
object OcrLayout {

    fun rebuild(lines: List<OcrLine>): String {
        if (lines.isEmpty()) return ""
        val medianH = lines.map { it.height }.sorted()[lines.size / 2].toDouble()
        val sorted = lines.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (l in sorted) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowCenter = row.map { it.centerY }.average()
                val overlapsHorizontally = row.any { it.left < l.right && l.left < it.right }
                if (kotlin.math.abs(l.centerY - rowCenter) <= medianH * 0.55 && !overlapsHorizontally) { row += l; continue }
            }
            rows += mutableListOf(l)
        }
        val sb = StringBuilder()
        var prevBottom = Int.MIN_VALUE
        for (row in rows) {
            val top = row.minOf { it.top }
            if (prevBottom != Int.MIN_VALUE && top - prevBottom > medianH * 1.6) sb.append('\n')
            val cells = row.sortedBy { it.left }
            val text = StringBuilder()
            cells.forEachIndexed { i, c ->
                if (i > 0) {
                    val gap = c.left - cells[i - 1].right
                    text.append(if (gap > medianH * 1.5) "  " else " ")
                }
                text.append(c.text.trim())
            }
            sb.append(text).append('\n')
            prevBottom = row.maxOf { it.bottom }
        }
        return sb.toString().trimEnd()
    }
}
