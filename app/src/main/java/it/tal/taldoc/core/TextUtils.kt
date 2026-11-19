package it.tal.taldoc.core

import java.text.Normalizer

object TextUtils {

    val stopWords: Set<String> = setOf(
        "a", "ad", "al", "alla", "alle", "allo", "ai", "agli", "all", "che", "chi", "ci", "con", "come", "cosa",
        "da", "dal", "dalla", "dalle", "dallo", "dai", "dagli", "dei", "del", "della", "delle", "dello", "degli",
        "di", "e", "ed", "era", "essere", "fa", "fai", "fammi", "il", "in", "io", "la", "le", "li", "lo", "ma",
        "mi", "ne", "nel", "nella", "nelle", "nello", "nei", "negli", "nell", "non", "o", "per", "piu", "quale",
        "quali", "quando", "quanto", "quanta", "quanti", "quante", "questa", "questo", "questi", "queste",
        "quello", "quella", "se", "sei", "si", "sia", "su", "sul", "sulla", "sui", "ti", "tu", "un", "una", "uno",
        "dammi", "dimmi", "trova", "trovami", "cerca", "cercami", "qual", "sono", "ha", "hanno", "ho", "ce",
        "l", "d", "dell", "c", "dice", "dire", "significa", "vuol", "vorrei", "sapere", "puoi", "potresti",
        "mostra", "mostrami", "indica", "indicami", "scrivi", "dov", "dove", "documento", "documenti", "file",
        "testo", "pagina", "presente", "scritto", "riportato", "contiene", "c'e", "the", "of", "and", "is",
        "what", "where", "which", "who", "to", "an", "my", "mio", "mia", "miei", "mie", "suo", "sua", "loro",
        "anche", "poi", "ora", "gia", "solo", "tutto", "tutti", "tutta", "tutte", "ogni", "molto", "po",
    )

    private val accents = Regex("\\p{Mn}+")

    fun fold(s: String): String =
        accents.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

    fun tokens(s: String): List<String> =
        fold(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Radice grossolana per l'italiano (fattura/fatture -> fattur). */
    fun stem(word: String): String {
        if (word.length <= 4 || word.any { it.isDigit() }) return word
        val suffixes = listOf("azioni", "azione", "amenti", "amento", "mente", "ita", "i", "e", "a", "o")
        for (suf in suffixes) {
            if (word.endsWith(suf) && word.length - suf.length >= 4) return word.dropLast(suf.length)
        }
        return word
    }

    fun keywords(query: String): List<String> =
        tokens(query).filter { (it.length >= 3 && it !in stopWords) || it.all(Char::isDigit) }.distinct()

    fun levenshtein(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Somiglianza 0..1 tra due stringhe (basata su distanza di edit). */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen > 400) return tokenSimilarity(a, b)
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    fun tokenSimilarity(a: String, b: String): Double {
        val ta = tokens(a).toSet()
        val tb = tokens(b).toSet()
        if (ta.isEmpty() && tb.isEmpty()) return 1.0
        val inter = ta.intersect(tb).size.toDouble()
        return inter / (ta.size + tb.size - inter)
    }

    fun normalizeLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** Chiave di confronto tollerante: minuscole, senza accenti né punteggiatura. */
    fun compareKey(s: String): String = tokens(s).joinToString(" ")

    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        for (block in text.split(Regex("\\n\\s*\\n|\\n"))) {
            val line = normalizeLine(block)
            if (line.isEmpty()) continue
            val parts = line.split(Regex("(?<=[.!?;])\\s+(?=[\\p{Lu}0-9«\"(])"))
            out += parts.map { it.trim() }.filter { it.isNotEmpty() }
        }
        return out
    }

    /** Parole con iniziale maiuscola o tutte maiuscole: probabili nomi/entità. */
    fun entities(text: String): List<String> {
        val re = Regex("\\b([\\p{Lu}][\\p{Lu}&.]{1,}(?:[ \\t]+[\\p{Lu}][\\p{Lu}&.]{1,})*|[\\p{Lu}][\\p{Ll}]+(?:[ \\t]+[\\p{Lu}][\\p{Ll}]+)+)\\b")
        return re.findAll(text).map { it.value.trim() }.filter { it.length >= 3 }.distinct().toList()
    }

    fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
}
