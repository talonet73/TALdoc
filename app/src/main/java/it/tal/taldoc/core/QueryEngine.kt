package it.tal.taldoc.core

import kotlin.math.ln

enum class Intent { ADDRESS, AMOUNT, DATE, NAME, EMAIL, PHONE, IBAN, TAX_ID, CODE, COUNT, YES_NO, GENERAL }

/**
 * Motore offline: recupero dei passaggi (BM25 con radici e tolleranza agli errori OCR),
 * riconoscimento dell'intento, estrazione mirata dei dati e richieste di chiarimento.
 */
class QueryEngine(private val docs: List<LoadedDocument>) {

    private data class Chunk(val document: String, val page: Int, val text: String, val terms: List<String>)

    private val chunks: List<Chunk> by lazy { buildChunks() }
    private val df: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        chunks.forEach { c -> c.terms.toSet().forEach { m[it] = (m[it] ?: 0) + 1 } }
        m
    }
    private val avgLen: Double by lazy { chunks.map { it.terms.size }.average().takeIf { !it.isNaN() } ?: 1.0 }
    val vocabulary: Set<String> by lazy { chunks.flatMap { c -> TextUtils.tokens(c.text) }.filter { it.length >= 3 }.toSet() }

    private fun buildChunks(): List<Chunk> {
        val out = mutableListOf<Chunk>()
        for (d in docs) for (p in d.pages) {
            val lines = p.text.lines().map { TextUtils.normalizeLine(it) }.filter { it.isNotEmpty() }
            // finestre di 1 riga con contesto (riga precedente e successiva) + frasi lunghe
            lines.forEachIndexed { i, line ->
                val ctx = buildString {
                    if (i > 0 && lines[i - 1].length < 120) append(lines[i - 1]).append('\n')
                    append(line)
                    if (i + 1 < lines.size && lines[i + 1].length < 120) append('\n').append(lines[i + 1])
                }
                out += Chunk(d.name, p.page, ctx, terms(line) + terms(ctx).map { "~$it" })
            }
        }
        return out
    }

    private fun terms(s: String) = TextUtils.tokens(s).filter { it !in TextUtils.stopWords || it.all(Char::isDigit) }.map { TextUtils.stem(it) }

    // ---------------------------------------------------------------- intento

    fun detectIntent(query: String): Intent {
        val q = TextUtils.fold(query)
        fun has(vararg w: String) = w.any { Regex("\\b$it").containsMatchIn(q) }
        return when {
            has("iban", "conto corrente", "coordinate bancarie") -> Intent.IBAN
            has("codice fiscale", "partita iva", "p\\.? ?iva", "cf\\b", "vat") -> Intent.TAX_ID
            has("email", "e-mail", "mail", "pec", "posta elettronica") -> Intent.EMAIL
            has("telefono", "cellulare", "numero di telefono", "tel\\b", "fax", "contatto") -> Intent.PHONE
            has("indirizzo", "sede", "residen", "abita", "dove si trova", "domicilio", "ubicat", "via\\b", "piazza") -> Intent.ADDRESS
            has("quanti", "quante", "numero di", "conta") -> Intent.COUNT
            has("importo", "paga", "stipendio", "totale", "netto", "lordo", "euro", "costo", "prezzo", "quanto cost",
                "quanto (si )?paga", "quanto ", "imponibile", "iva\\b", "saldo", "rata", "spesa", "compenso", "tariffa") || q.contains("€") -> Intent.AMOUNT
            has("data", "quando", "scadenza", "giorno", "entro", "dal\\b", "nato", "nata") -> Intent.DATE
            has("chi\\b", "nome", "dipendente", "persona", "intestat", "cliente", "fornitore", "firmat", "titolare", "azienda", "societa", "ditta") -> Intent.NAME
            Regex("\\b\\d{3,}\\b").containsMatchIn(q) || has("codice", "cod\\.?\\b", "voce") -> Intent.CODE
            has("c'e", "ce\\b", "esiste", "e presente", "e previsto", "contiene", "si parla", "menziona") -> Intent.YES_NO
            else -> Intent.GENERAL
        }
    }

    // ---------------------------------------------------------------- recupero

    fun retrieve(query: String, limit: Int = 8, extraTerms: List<String> = emptyList()): List<Passage> {
        val kw = (TextUtils.keywords(query) + extraTerms).distinct()
        if (kw.isEmpty()) return emptyList()
        val qTerms = kw.map { TextUtils.stem(it) }.flatMap { t -> listOf(t) + fuzzyVariants(t) }.distinct()
        val n = chunks.size.toDouble()
        val entities = TextUtils.entities(query).map { TextUtils.fold(it) }
        val scored = chunks.map { c ->
            var score = 0.0
            val len = c.terms.size.toDouble()
            for (t in qTerms) {
                for ((prefix, weight) in listOf("" to 1.0, "~" to 0.35)) {
                    val term = prefix + t
                    val tf = c.terms.count { it == term }
                    if (tf == 0) continue
                    val dfv = (df[term] ?: 1).toDouble()
                    val idf = ln(1 + (n - dfv + 0.5) / (dfv + 0.5))
                    score += weight * idf * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * len / avgLen))
                }
            }
            val folded = TextUtils.fold(c.text)
            entities.forEach { if (folded.contains(it)) score += 3.0 }
            // bonus per frasi che contengono più parole chiave diverse
            val distinctHits = kw.count { folded.contains(TextUtils.stem(it)) }
            if (distinctHits >= 2) score *= 1.0 + 0.3 * (distinctHits - 1)
            Passage(c.document, c.page, c.text, score)
        }.filter { it.score > 0 }
        return scored.sortedByDescending { it.score }.distinctBy { it.document + it.page + it.text.lines().getOrNull(1) }.take(limit)
    }

    private val fuzzyCache = HashMap<String, List<String>>()
    private val stemVocabulary: Set<String> by lazy { df.keys.filter { !it.startsWith("~") }.toSet() }

    /** Varianti presenti nei documenti a distanza 1 (errori di OCR o battitura). */
    private fun fuzzyVariants(stem: String): List<String> = fuzzyCache.getOrPut(stem) {
        if (stem.length < 5 || stem.all(Char::isDigit)) return@getOrPut emptyList()
        stemVocabulary.filter { it != stem && kotlin.math.abs(it.length - stem.length) <= 1 && TextUtils.levenshtein(it, stem, 1) <= 1 }.take(5)
    }

    /** Suggerisce parole simili presenti nei documenti per una parola non trovata. */
    fun suggestions(word: String, max: Int = 4): List<String> {
        val w = TextUtils.fold(word)
        val limit = if (w.length <= 5) 1 else 2
        return vocabulary.asSequence()
            .filter { kotlin.math.abs(it.length - w.length) <= limit && it.first() == w.first() }
            .map { it to TextUtils.levenshtein(it, w, limit) }
            .filter { it.second in 1..limit }
            .sortedBy { it.second }
            .map { it.first }.take(max).toList()
    }

    // ---------------------------------------------------------------- chiarimenti

    private val vagueRe = Regex(
        "^(dimmi tutto|tutto|cosa c'?e( scritto)?|di cosa parla|spiegami|aiuto|help|info|informazioni|analizza|leggi|" +
            "che cos'?e( questo)?|cos'?e( questo)?|ok|si|no|boh|non so)\\??$"
    )

    /** Ritorna un chiarimento se la domanda è troppo vaga o non trova riscontro, altrimenti null. */
    fun clarification(query: String): EngineResult.Clarify? {
        val q = query.trim()
        val folded = TextUtils.fold(q).replace(Regex("[!?.]+$"), "").trim()
        if (q.isEmpty()) {
            return EngineResult.Clarify("Cosa vuoi sapere dai documenti?", starterOptions(), q)
        }
        if (vagueRe.matches(folded)) {
            return EngineResult.Clarify(
                "La richiesta è un po' generica. Cosa preferisci?",
                listOf(
                    ClarifyOption("Riassunto dei documenti", "", Mode.SUMMARY),
                    ClarifyOption("Trascrizione completa", "", Mode.TRANSCRIPTION),
                    ClarifyOption("Verifica dei conti", "", Mode.CALC),
                ) + starterOptions().take(2),
                q
            )
        }
        val kw = TextUtils.keywords(q)
        val intent = detectIntent(q)
        if (kw.isEmpty() && intent == Intent.GENERAL) {
            return EngineResult.Clarify(
                "Non ho capito cosa cercare. Puoi indicare un dato preciso (es. un nome, un importo, una data, un codice)?",
                starterOptions(), q
            )
        }
        val contentKw = kw.filter { !isIntentWord(it) }
        if (contentKw.isNotEmpty()) {
            val found = contentKw.filter { k -> foundInDocs(k) }
            if (found.isEmpty()) {
                val sugg = contentKw.flatMap { suggestions(it) }.distinct().take(5)
                return if (sugg.isNotEmpty()) EngineResult.Clarify(
                    "Non trovo «${contentKw.joinToString(" ")}» nei documenti. Forse intendevi:",
                    sugg.map { s -> ClarifyOption(s, replaceFirstMissing(q, contentKw, s)) }, q
                ) else EngineResult.Clarify(
                    "Non trovo «${contentKw.joinToString(" ")}» nei documenti caricati. Vuoi cercare altro?",
                    starterOptions(), q
                )
            }
        }
        return null
    }

    private val intentWords = setOf(
        "indirizzo", "importo", "totale", "data", "nome", "email", "mail", "telefono", "codice", "iban", "fiscale",
        "partita", "iva", "quanto", "quando", "chi", "sede", "costo", "prezzo", "paga", "netto", "lordo", "scadenza",
        "cellulare", "numero", "quanti", "quante", "valore", "cifra", "somma", "euro", "giorno", "persona",
    )
    private fun isIntentWord(w: String) = TextUtils.fold(w) in intentWords

    private fun foundInDocs(k: String): Boolean {
        val f = TextUtils.fold(k)
        val st = TextUtils.stem(f)
        return vocabulary.any { it.startsWith(st) } || fuzzyVariants(st).isNotEmpty()
    }

    private fun replaceFirstMissing(q: String, kws: List<String>, s: String): String {
        val missing = kws.firstOrNull { !foundInDocs(it) } ?: return "$q $s"
        return Regex("(?i)\\b${Regex.escape(missing)}\\b").replaceFirst(q, s).let { if (it == q) "$q $s" else it }
    }

    /** Proposte di domande basate sul contenuto dei documenti. */
    fun starterOptions(): List<ClarifyOption> {
        val all = docs.joinToString("\n") { it.fullText }
        val opts = mutableListOf<ClarifyOption>()
        val folded = TextUtils.fold(all)
        if (Regex("total|import|€|euro").containsMatchIn(folded)) opts += ClarifyOption("Qual è il totale?", "qual è il totale")
        if (Regex("\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b").containsMatchIn(all)) opts += ClarifyOption("Quali date ci sono?", "quali sono le date")
        if (Regex("\\b(via|viale|piazza|corso)\\b").containsMatchIn(folded)) opts += ClarifyOption("Qual è l'indirizzo?", "qual è l'indirizzo")
        if (Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+").containsMatchIn(all)) opts += ClarifyOption("Email presenti", "quali email ci sono")
        val ent = TextUtils.entities(all).filter { it.length in 4..30 }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(2).map { it.key }
        ent.forEach { opts += ClarifyOption("Cosa dice su $it?", "cosa dice su $it") }
        if (opts.isEmpty()) opts += ClarifyOption("Riassunto", "", Mode.SUMMARY)
        return opts.take(5)
    }

    // ---------------------------------------------------------------- risposta

    private val extractors: Map<Intent, Regex> = mapOf(
        Intent.EMAIL to Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"),
        Intent.IBAN to Regex("\\b[A-Z]{2}\\d{2}\\s?(?:[A-Z0-9]{4}\\s?){3,7}[A-Z0-9]{1,4}\\b"),
        Intent.TAX_ID to Regex("\\b([A-Z]{6}\\d{2}[A-Z]\\d{2}[A-Z]\\d{3}[A-Z]|(?:IT)?\\d{11})\\b", RegexOption.IGNORE_CASE),
        Intent.PHONE to Regex("(?:\\+39[\\s.]?)?(?:0\\d{1,3}|3\\d{2})[\\s./-]?\\d{3,4}[\\s./-]?\\d{3,4}"),
        Intent.DATE to Regex("\\b(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{1,2}\\s+(?:gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)\\s+\\d{4})\\b", RegexOption.IGNORE_CASE),
        Intent.ADDRESS to Regex("\\b(?:via|viale|v\\.le|piazza|p\\.za|corso|c\\.so|largo|vicolo|strada|località|loc\\.)\\s+[^,\\n]{2,50}(?:,?\\s*(?:n\\.?\\s*)?\\d{1,4}[a-zA-Z]?(?!\\d))?(?:[,\\s-]+\\d{5}\\s+[^,\\n]{2,30})?", RegexOption.IGNORE_CASE),
    )

    fun answer(query: String): EngineResult {
        if (docs.isEmpty()) return EngineResult.Answer("Nessun documento caricato. Importa un file o scatta una foto.")
        clarification(query)?.let { return it }
        val intent = detectIntent(query)

        // codici numerici (es. "codice 8002")
        val codes = Regex("\\b\\d{3,}\\b").findAll(query).map { it.value }.toList()
        if (codes.isNotEmpty() && intent in setOf(Intent.CODE, Intent.GENERAL, Intent.AMOUNT, Intent.YES_NO)) {
            val hits = docs.flatMap { d ->
                d.pages.flatMap { p ->
                    p.text.lines().filter { line -> codes.any { c -> Regex("(?<!\\d)$c(?!\\d)").containsMatchIn(line) } }
                        .map { "${d.name} · pag. ${p.page}: ${it.trim()}" }
                }
            }.distinct()
            if (hits.isNotEmpty()) {
                return EngineResult.Answer(
                    "Righe che contengono ${codes.joinToString(", ")}:\n\n" + hits.take(10).joinToString("\n") { "• $it" } +
                        if (hits.size > 10) "\n… e altre ${hits.size - 10}" else ""
                )
            }
        }

        if (intent == Intent.AMOUNT || intent == Intent.GENERAL) tableLookup(query)?.let { return it }

        val passages = retrieve(query, 10)

        // conteggio
        if (intent == Intent.COUNT) {
            val target = TextUtils.keywords(query).filter { !isIntentWord(it) }.map { TextUtils.stem(it) }
            if (target.isNotEmpty()) {
                val counts = docs.associate { d ->
                    d.name to TextUtils.tokens(d.fullText).count { tok -> target.any { tok.startsWith(it) } }
                }
                val tot = counts.values.sum()
                return EngineResult.Answer(
                    "«${target.joinToString(" ")}» compare $tot volte" +
                        (if (docs.size > 1) ":\n" + counts.entries.joinToString("\n") { "• ${it.key}: ${it.value}" } else ".") +
                        (passages.firstOrNull()?.let { "\n\nEsempio (${it.ref}):\n${it.text}" } ?: "")
                )
            }
        }

        if (passages.isEmpty()) {
            // per intenti estraibili cerca il dato in tutto il testo
            val direct = extractEverywhere(intent)
            if (direct.isNotEmpty()) return EngineResult.Answer(direct.joinToString("\n"), emptyList())
            return EngineResult.Clarify(
                "Non ho trovato passaggi pertinenti. Puoi riformulare con una parola presente nel documento?",
                starterOptions(), query
            )
        }

        // estrazione mirata
        val regex = extractors[intent]
        if (regex != null) {
            val values = passages.flatMap { p -> regex.findAll(p.text).map { it.value.trim() to p }.toList() }
                .distinctBy { TextUtils.compareKey(it.first) }
            if (values.isNotEmpty()) {
                val title = when (intent) {
                    Intent.EMAIL -> "Email"; Intent.IBAN -> "IBAN"; Intent.TAX_ID -> "Codice fiscale / P.IVA"
                    Intent.PHONE -> "Telefono"; Intent.DATE -> "Date"; Intent.ADDRESS -> "Indirizzo"; else -> "Risultato"
                }
                val main = values.first()
                val sb = StringBuilder("$title: ${main.first}\n(${main.second.ref})\n\nContesto:\n${main.second.text}")
                if (values.size > 1) {
                    sb.append("\n\nAltri valori trovati:\n")
                    values.drop(1).take(6).forEach { sb.append("• ${it.first} — ${it.second.ref}\n") }
                }
                return EngineResult.Answer(sb.toString().trimEnd(), passages.map { it.ref }.distinct())
            }
        }

        if (intent == Intent.AMOUNT) {
            val qStems = TextUtils.keywords(query).map { TextUtils.stem(it) }
            val specific = TextUtils.keywords(query).filter { !isIntentWord(it) }.map { TextUtils.stem(it) }
            if (specific.isEmpty()) {
                // domanda generica ("quanto è l'importo?", "qual è il totale?"): guarda tutte le righe con importi chiave
                val keyRe = Regex("\\b(totale|netto|importo|imponibile|saldo|da pagare|iva|lordo|paga|stipendio|prezzo|costo)\\b")
                val all = docs.flatMap { d ->
                    d.pages.flatMap { p ->
                        p.text.lines().map { it.trim() }.filter { Numbers.monetary(it).isNotEmpty() && keyRe.containsMatchIn(TextUtils.fold(it)) }
                            .map { Triple(it, d.name, p.page) }
                    }
                }.distinctBy { labelKey(it.first) }
                val narrowed = all.filter { (l, _, _) -> val f = TextUtils.fold(l); qStems.any { f.contains(it) } }.ifEmpty { all }
                if (narrowed.size == 1) {
                    val (l, dn, pg) = narrowed[0]
                    return EngineResult.Answer("Importo: € ${Numbers.format(Numbers.monetary(l).last().value)}\n«$l»\n($dn · pag. $pg)")
                }
                if (narrowed.size in 2..8) {
                    return EngineResult.Clarify(
                        "Ho trovato più importi. Quale ti interessa?",
                        narrowed.take(6).map { (l, _, _) -> ClarifyOption(TextUtils.truncate(l, 60), "importo ${labelOf(l)}") },
                        query
                    )
                }
            }
            data class Cand(val line: String, val p: Passage, val score: Double)
            val cands = passages.flatMap { p ->
                p.text.lines().filter { Numbers.monetary(it).isNotEmpty() || Numbers.find(it).isNotEmpty() && it.contains('|') }.map { line ->
                    val f = TextUtils.fold(line)
                    var s = p.score
                    s += 2.0 * qStems.count { f.contains(it) }
                    s += 3.0 * specific.count { f.contains(it) }
                    if (Regex("\\b(totale|netto|importo|saldo|da pagare)\\b").containsMatchIn(f)) s += 1.0
                    if (line == centralLine(p)) s += 0.5
                    Cand(line.trim(), p, s)
                }
            }.sortedByDescending { it.score }.distinctBy { it.line }
            if (cands.isNotEmpty()) {
                val top = cands.first()
                // ambiguità: più importi con etichette diverse e punteggio simile, domanda non specifica
                val close = cands.filter { it.score >= top.score * 0.8 }.distinctBy { labelKey(it.line) }
                val topHasSpecific = specific.isNotEmpty() && specific.all { TextUtils.fold(top.line).contains(it) }
                if (close.size >= 2 && !topHasSpecific) {
                    return EngineResult.Clarify(
                        "Ho trovato più importi che potrebbero corrispondere. Quale ti interessa?",
                        close.take(5).map { ClarifyOption(TextUtils.truncate(it.line, 60), "importo ${labelOf(it.line)}") },
                        query
                    )
                }
                val amounts = Numbers.monetary(top.line).ifEmpty { Numbers.find(top.line) }
                val value = amounts.lastOrNull()?.let { "€ ${Numbers.format(it.value)}" } ?: ""
                return EngineResult.Answer(
                    "Importo: $value\n«${top.line}»\n(${top.p.ref})" +
                        cands.drop(1).take(3).let { rest -> if (rest.isEmpty()) "" else "\n\nAltri importi vicini:\n" + rest.joinToString("\n") { "• ${it.line} — ${it.p.ref}" } },
                    passages.map { it.ref }.distinct()
                )
            }
        }

        if (intent == Intent.YES_NO) {
            val top = passages.first()
            val strong = top.score >= 2.0
            return EngineResult.Answer(
                (if (strong) "Sì, nei documenti se ne parla.\n\n" else "Non ho trovato un riferimento esplicito; il passaggio più vicino è:\n\n") +
                    "${top.ref}:\n${top.text}" + otherPassages(passages.drop(1)),
                passages.map { it.ref }.distinct()
            )
        }

        val top = passages.first()
        return EngineResult.Answer(
            "${top.ref}:\n${top.text}" + otherPassages(passages.drop(1)),
            passages.map { it.ref }.distinct()
        )
    }

    /** Ricerca riga × colonna nelle tabelle (Excel, CSV, tabelle Word). */
    fun tableLookup(query: String): EngineResult.Answer? {
        val kw = TextUtils.keywords(query).filter { !isIntentWord(it) }.map { TextUtils.stem(it) }
        if (kw.isEmpty()) return null
        val results = mutableListOf<String>()
        for (d in docs) for (t in d.tables) {
            if (t.rows.size < 2) continue
            val headerIdx = t.rows.indexOfFirst { r ->
                val filled = r.count { it.isNotBlank() }
                filled >= 2 && r.count { it.isNotBlank() && Numbers.parseCell(it) == null } >= maxOf(2, (filled + 1) / 2)
            }
            if (headerIdx < 0) continue
            val header = t.rows[headerIdx]
            val colHits = header.indices.filter { c -> c > 0 && header[c].isNotBlank() && kw.any { TextUtils.fold(header[c]).contains(it) } }
            for (r in headerIdx + 1 until t.rows.size) {
                val row = t.rows[r]
                val labelIdx = row.indexOfFirst { it.isNotBlank() && Numbers.parseCell(it) == null }
                if (labelIdx < 0) continue
                val label = TextUtils.fold(row[labelIdx])
                if (kw.none { label.contains(it) }) continue
                if (colHits.isNotEmpty()) {
                    colHits.forEach { c ->
                        row.getOrNull(c)?.takeIf { it.isNotBlank() }?.let { results += "${row[labelIdx]} / ${header[c]}: $it  (${d.name}, ${t.name})" }
                    }
                } else {
                    val values = row.indices.filter { it != labelIdx && row[it].isNotBlank() }
                    if (values.isEmpty()) continue
                    results += "${row[labelIdx]}: " + values.joinToString(" · ") { c ->
                        "${header.getOrNull(c)?.ifBlank { null } ?: CalcChecker.columnName(c)} ${row[c]}"
                    } + "  (${d.name}, ${t.name})"
                }
            }
        }
        if (results.isEmpty()) return null
        return EngineResult.Answer("Dalla tabella:\n" + results.distinct().take(8).joinToString("\n") { "• $it" })
    }

    private fun centralLine(p: Passage): String {
        val ls = p.text.lines()
        return if (ls.size >= 3) ls[1] else if (ls.size == 2) {
            if (Numbers.monetary(ls[0]).isNotEmpty() && Numbers.monetary(ls[1]).isEmpty()) ls[0] else ls[1]
        } else ls[0]
    }

    private fun labelOf(line: String) = line.replace('|', ' ').replace(Regex("[-€]?\\s?\\d[\\d.,']*\\s?(€|%|EUR)?"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd(':', '.', '-', ' ').ifEmpty { line }

    private fun labelKey(line: String) = TextUtils.compareKey(labelOf(line))

    private fun queryHasSpecificLabel(query: String, labels: List<String>): Boolean {
        val qk = TextUtils.keywords(query).filter { !isIntentWord(it) }.map { TextUtils.stem(it) }
        if (qk.isEmpty()) return false
        val matching = labels.filter { l -> val lk = TextUtils.fold(l); qk.all { lk.contains(it) } }
        return matching.size == 1
    }

    private fun otherPassages(rest: List<Passage>): String {
        if (rest.isEmpty()) return ""
        return "\n\nAltri passaggi pertinenti:\n" + rest.take(3).joinToString("\n\n") { "${it.ref}:\n${it.text}" }
    }

    private fun extractEverywhere(intent: Intent): List<String> {
        val re = extractors[intent] ?: return emptyList()
        return docs.flatMap { d -> d.pages.flatMap { p -> re.findAll(p.text).map { "• ${it.value.trim()} — ${d.name} · pag. ${p.page}" }.toList() } }
            .distinct().take(10)
    }

    // ---------------------------------------------------------------- altre modalità

    fun search(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "Scrivi una parola o una frase da cercare."
        val fq = TextUtils.fold(q)
        val hits = mutableListOf<String>()
        for (d in docs) for (p in d.pages) for (line in p.text.lines()) {
            if (TextUtils.fold(line).contains(fq)) hits += "${d.name} · pag. ${p.page}: ${line.trim()}"
        }
        if (hits.isNotEmpty()) {
            return "Trovate ${hits.size} occorrenze di «$q»:\n\n" + hits.take(50).joinToString("\n") { "• $it" } +
                if (hits.size > 50) "\n… e altre ${hits.size - 50}" else ""
        }
        val sugg = TextUtils.keywords(q).flatMap { suggestions(it) }.distinct()
        val fuzzy = retrieve(q, 5)
        return buildString {
            append("Nessuna occorrenza esatta di «$q».")
            if (sugg.isNotEmpty()) append("\nParole simili nei documenti: ${sugg.joinToString(", ")}.")
            if (fuzzy.isNotEmpty()) {
                append("\n\nPassaggi più vicini:\n")
                fuzzy.forEach { append("• ${it.ref}: ${it.text.replace('\n', ' ')}\n") }
            }
        }.trimEnd()
    }

    fun transcription(filter: String): String {
        val f = TextUtils.fold(filter.trim())
        val selected = docs.filter { f.isEmpty() || TextUtils.fold(it.name).contains(f) }.ifEmpty { docs }
        return selected.joinToString("\n\n════════\n\n") { d ->
            "📄 ${d.name} (${d.kind}, ${d.pages.size} pag.)\n" + d.notes.joinToString("") { "⚠ $it\n" } +
                d.pages.joinToString("\n\n") { p -> "— Pagina ${p.page} —\n${p.text.ifBlank { "(nessun testo riconosciuto)" }}" }
        }
    }
}
