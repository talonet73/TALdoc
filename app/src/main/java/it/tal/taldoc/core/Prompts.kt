package it.tal.taldoc.core

/** Prompt per il modello linguistico locale (Gemma o simili). */
object Prompts {

    const val CLARIFY_TAG = "CHIARIMENTO:"

    /** Gemma 3 1B int4 ha un contesto di 2048 token: circa 3500 caratteri di estratti lasciano spazio alla risposta. */
    const val CONTEXT_CHARS = 3500

    val system = """
        Sei TALdoc, un assistente che analizza documenti in italiano.
        Regole:
        1. Rispondi SOLO usando gli estratti dei documenti forniti. Non inventare nulla.
        2. Rispondi in italiano, in modo breve e preciso. Riporta i valori esattamente come scritti (importi, date, codici).
        3. Indica la fonte tra parentesi, ad esempio (fattura.pdf, pag. 2).
        4. Se l'informazione non è presente negli estratti, rispondi: "Non ho trovato questa informazione nei documenti."
        5. Se la domanda è ambigua, troppo generica o può riferirsi a più cose diverse, NON rispondere:
           scrivi "$CLARIFY_TAG" seguito da una sola domanda breve per chiedere all'utente cosa intende.
    """.trimIndent()

    fun context(passages: List<Passage>, maxChars: Int = CONTEXT_CHARS): String {
        val sb = StringBuilder()
        for (p in passages) {
            val block = "[${p.document}, pag. ${p.page}]\n${p.text.trim()}\n\n"
            if (sb.length + block.length > maxChars) break
            sb.append(block)
        }
        return sb.toString().trim()
    }

    fun question(query: String, passages: List<Passage>, history: List<Pair<String, String>> = emptyList()): String = buildString {
        append("ESTRATTI DAI DOCUMENTI:\n")
        append(context(passages))
        if (history.isNotEmpty()) {
            append("\n\nCONVERSAZIONE PRECEDENTE:\n")
            history.takeLast(1).forEach { (q, a) -> append("Utente: ${TextUtils.truncate(q, 200)}\nTALdoc: ${TextUtils.truncate(a, 200)}\n") }
        }
        append("\n\nDOMANDA: ").append(query.trim())
        append("\n\nRISPOSTA:")
    }

    fun summary(docName: String, text: String, focus: String): String = buildString {
        append("Riassumi in italiano il seguente testo tratto da «$docName» in 5-8 punti elenco brevi. ")
        append("Includi importi, date, nomi e scadenze importanti esattamente come scritti. Non aggiungere informazioni esterne.")
        if (focus.isNotBlank()) append(" Concentrati in particolare su: ${focus.trim()}.")
        append("\n\nTESTO:\n").append(text.trim()).append("\n\nRIASSUNTO:")
    }

    fun mergeSummaries(docName: String, parts: List<String>): String = buildString {
        append("Unisci questi riassunti parziali di «$docName» in un unico riassunto in italiano di 6-10 punti elenco, senza ripetizioni.\n\n")
        parts.forEachIndexed { i, s -> append("PARTE ${i + 1}:\n$s\n\n") }
        append("RIASSUNTO FINALE:")
    }

    /** Se il modello chiede un chiarimento ritorna la domanda, altrimenti null. */
    fun parseClarification(output: String): String? {
        val idx = output.indexOf(CLARIFY_TAG, ignoreCase = true)
        if (idx < 0 || idx > 40) return null
        return output.substring(idx + CLARIFY_TAG.length).trim().ifEmpty { null }
    }

    /** Divide un testo lungo in blocchi per il riassunto a più passaggi. */
    fun chunks(text: String, size: Int = CONTEXT_CHARS, max: Int = 5): List<String> {
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (para in text.split(Regex("\\n\\s*\\n"))) {
            if (cur.length + para.length > size && cur.isNotEmpty()) { out += cur.toString(); cur = StringBuilder() }
            if (para.length > size) { para.chunked(size).forEach { out += it } } else cur.append(para).append("\n\n")
            if (out.size >= max) break
        }
        if (cur.isNotBlank() && out.size < max) out += cur.toString()
        return out.take(max)
    }
}
