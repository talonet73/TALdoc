package it.tal.taldoc.core

/** Confronto tra due documenti: righe mancanti, aggiunte, modificate e valori cambiati. */
object DocCompare {

    data class Line(val page: Int, val text: String) {
        val key: String = TextUtils.compareKey(text)
    }

    data class Result(
        val onlyA: List<Line>,
        val onlyB: List<Line>,
        val changed: List<Pair<Line, Line>>,
        val valueChanges: List<String>,
        val similarity: Double,
        val equalLines: Int,
    )

    fun lines(doc: LoadedDocument): List<Line> = doc.pages.flatMap { p ->
        p.text.lines().map { TextUtils.normalizeLine(it) }.filter { it.length >= 2 }.map { Line(p.page, it) }
    }.filter { it.key.isNotEmpty() }

    fun compare(a: LoadedDocument, b: LoadedDocument): Result {
        val la = lines(a)
        val lb = lines(b)
        val ops = diff(la.map { it.key }, lb.map { it.key })
        val onlyA = mutableListOf<Line>()
        val onlyB = mutableListOf<Line>()
        val changed = mutableListOf<Pair<Line, Line>>()
        var equal = 0
        // raggruppa le sequenze di cancellazioni/inserimenti tra righe uguali
        var i = 0
        while (i < ops.size) {
            if (ops[i].type == '=') { equal++; i++; continue }
            val dels = mutableListOf<Line>()
            val ins = mutableListOf<Line>()
            while (i < ops.size && ops[i].type != '=') {
                if (ops[i].type == '-') dels += la[ops[i].a] else ins += lb[ops[i].b]
                i++
            }
            pairUp(dels, ins, onlyA, onlyB, changed).also { equal += it }
        }
        // righe spostate: presenti in entrambi ma in posizioni diverse
        val movedKeysA = onlyA.map { it.key }.toMutableList()
        val movedB = onlyB.filter { lbLine ->
            val idx = movedKeysA.indexOf(lbLine.key)
            if (idx >= 0) { movedKeysA.removeAt(idx); true } else false
        }
        val movedKeys = movedB.map { it.key }.toMutableList()
        val realOnlyA = onlyA.filter { l -> val k = movedKeys.indexOf(l.key); if (k >= 0) { movedKeys.removeAt(k); false } else true }
        val realOnlyB = onlyB.filter { it !in movedB }
        equal += movedB.size

        val valueChanges = mutableListOf<String>()
        for ((x, y) in changed) {
            val nx = Numbers.find(x.text).map { it.value }
            val ny = Numbers.find(y.text).map { it.value }
            if (nx.isNotEmpty() && ny.isNotEmpty() && nx != ny) {
                val label = labelOf(x.text).ifEmpty { labelOf(y.text) }
                val fx = nx.joinToString(" / ") { fmt(it) }
                val fy = ny.joinToString(" / ") { fmt(it) }
                valueChanges += "${label.ifEmpty { "Valore" }}: $fx → $fy"
            }
        }
        val total = (la.size + lb.size).coerceAtLeast(1)
        val similarity = (2.0 * equal + changed.size) / total
        return Result(realOnlyA, realOnlyB, changed, valueChanges, similarity.coerceIn(0.0, 1.0), equal)
    }

    private fun fmt(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString() else Numbers.format(v)

    private fun labelOf(s: String) = s.replace(Regex("[-€]?\\s?\\d[\\d.,']*\\s?(€|%|EUR)?"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd(':', '.', '-', ' ')

    /** Accoppia righe cancellate e inserite simili come "modificate". Ritorna quante sono di fatto uguali (rumore OCR). */
    private fun pairUp(
        dels: List<Line>, ins: List<Line>,
        onlyA: MutableList<Line>, onlyB: MutableList<Line>, changed: MutableList<Pair<Line, Line>>,
    ): Int {
        var equalByNoise = 0
        val usedIns = BooleanArray(ins.size)
        for (d in dels) {
            var best = -1
            var bestSim = 0.0
            for ((k, n) in ins.withIndex()) {
                if (usedIns[k]) continue
                val sim = TextUtils.similarity(d.key, n.key)
                if (sim > bestSim) { bestSim = sim; best = k }
            }
            when {
                best >= 0 && bestSim >= 0.93 && sameNumbers(d.text, ins[best].text) -> { usedIns[best] = true; equalByNoise++ }
                best >= 0 && bestSim >= 0.55 -> { usedIns[best] = true; changed += d to ins[best] }
                best >= 0 && sameLabelDifferentNumbers(d.text, ins[best].text) -> { usedIns[best] = true; changed += d to ins[best] }
                else -> onlyA += d
            }
        }
        ins.forEachIndexed { k, n -> if (!usedIns[k]) onlyB += n }
        return equalByNoise
    }

    private fun sameNumbers(a: String, b: String) = Numbers.find(a).map { it.value } == Numbers.find(b).map { it.value }

    private fun sameLabelDifferentNumbers(a: String, b: String): Boolean {
        val la = TextUtils.compareKey(labelOf(a))
        val lb = TextUtils.compareKey(labelOf(b))
        return la.length >= 3 && la == lb && !sameNumbers(a, b)
    }

    data class Op(val type: Char, val a: Int, val b: Int)

    /** Diff LCS su righe (con taglio di prefisso/suffisso comuni). */
    fun diff(a: List<String>, b: List<String>): List<Op> {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val ops = mutableListOf<Op>()
        for (k in 0 until pre) ops += Op('=', k, k)
        val ma = a.subList(pre, a.size - suf)
        val mb = b.subList(pre, b.size - suf)
        val n = ma.size
        val m = mb.size
        if (n.toLong() * m <= 6_000_000L) {
            val w = m + 1
            val dp = IntArray((n + 1) * w)
            for (x in n - 1 downTo 0) for (y in m - 1 downTo 0) {
                dp[x * w + y] = if (ma[x] == mb[y]) dp[(x + 1) * w + y + 1] + 1
                else maxOf(dp[(x + 1) * w + y], dp[x * w + y + 1])
            }
            var x = 0
            var y = 0
            while (x < n && y < m) {
                when {
                    ma[x] == mb[y] -> { ops += Op('=', pre + x, pre + y); x++; y++ }
                    dp[(x + 1) * w + y] >= dp[x * w + y + 1] -> { ops += Op('-', pre + x, -1); x++ }
                    else -> { ops += Op('+', -1, pre + y); y++ }
                }
            }
            while (x < n) { ops += Op('-', pre + x, -1); x++ }
            while (y < m) { ops += Op('+', -1, pre + y); y++ }
        } else {
            // documenti enormi: confronto per insiemi
            val countB = mb.groupingBy { it }.eachCount().toMutableMap()
            ma.forEachIndexed { x, s ->
                val c = countB[s] ?: 0
                if (c > 0) { countB[s] = c - 1; ops += Op('=', pre + x, -1) } else ops += Op('-', pre + x, -1)
            }
            val countA = ma.groupingBy { it }.eachCount().toMutableMap()
            mb.forEachIndexed { y, s ->
                val c = countA[s] ?: 0
                if (c > 0) countA[s] = c - 1 else ops += Op('+', -1, pre + y)
            }
        }
        for (k in 0 until suf) ops += Op('=', a.size - suf + k, b.size - suf + k)
        return ops
    }

    private fun wordDiff(a: String, b: String): String {
        val ta = a.split(Regex("\\s+"))
        val tb = b.split(Regex("\\s+"))
        val ka = ta.map { TextUtils.compareKey(it) }
        val kb = tb.map { TextUtils.compareKey(it) }
        val removed = ta.filterIndexed { i, _ -> ka[i].isNotEmpty() && ka[i] !in kb }
        val added = tb.filterIndexed { i, _ -> kb[i].isNotEmpty() && kb[i] !in ka }
        val parts = mutableListOf<String>()
        if (removed.isNotEmpty()) parts += "tolto: «${removed.joinToString(" ")}»"
        if (added.isNotEmpty()) parts += "aggiunto: «${added.joinToString(" ")}»"
        return parts.joinToString("; ")
    }

    fun report(a: LoadedDocument, b: LoadedDocument, maxItems: Int = 40): String {
        val r = compare(a, b)
        val sb = StringBuilder()
        val pct = (r.similarity * 100).toInt()
        sb.append("Confronto tra\n A = ${a.name}\n B = ${b.name}\n\n")
        sb.append("Somiglianza: $pct% · righe uguali: ${r.equalLines} · modificate: ${r.changed.size} · ")
        sb.append("mancanti in B: ${r.onlyA.size} · in più in B: ${r.onlyB.size}\n")
        if (r.onlyA.isEmpty() && r.onlyB.isEmpty() && r.changed.isEmpty()) {
            sb.append("\n✅ I due documenti hanno lo stesso contenuto (a meno di differenze di formattazione).")
            return sb.toString()
        }
        if (r.valueChanges.isNotEmpty()) {
            sb.append("\n🔢 VALORI CAMBIATI (${r.valueChanges.size})\n")
            r.valueChanges.take(maxItems).forEach { sb.append("• $it\n") }
        }
        if (r.changed.isNotEmpty()) {
            sb.append("\n✏️ RIGHE MODIFICATE (${r.changed.size})\n")
            r.changed.take(maxItems).forEach { (x, y) ->
                sb.append("• A pag.${x.page}: ${TextUtils.truncate(x.text, 140)}\n  B pag.${y.page}: ${TextUtils.truncate(y.text, 140)}\n")
                val wd = wordDiff(x.text, y.text)
                if (wd.isNotEmpty()) sb.append("  ($wd)\n")
            }
            if (r.changed.size > maxItems) sb.append("  … e altre ${r.changed.size - maxItems}\n")
        }
        if (r.onlyA.isNotEmpty()) {
            sb.append("\n➖ PRESENTI IN A MA MANCANTI IN B (${r.onlyA.size})\n")
            r.onlyA.take(maxItems).forEach { sb.append("• pag.${it.page}: ${TextUtils.truncate(it.text, 160)}\n") }
            if (r.onlyA.size > maxItems) sb.append("  … e altre ${r.onlyA.size - maxItems}\n")
        }
        if (r.onlyB.isNotEmpty()) {
            sb.append("\n➕ PRESENTI IN B MA NON IN A (${r.onlyB.size})\n")
            r.onlyB.take(maxItems).forEach { sb.append("• pag.${it.page}: ${TextUtils.truncate(it.text, 160)}\n") }
            if (r.onlyB.size > maxItems) sb.append("  … e altre ${r.onlyB.size - maxItems}\n")
        }
        return sb.toString().trimEnd()
    }
}
