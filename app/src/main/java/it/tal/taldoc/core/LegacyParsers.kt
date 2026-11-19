package it.tal.taldoc.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** Lettore minimale di file OLE2 / Compound File Binary (formato dei .doc e .xls). */
class CompoundFile(private val data: ByteArray) {
    private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries = mutableListOf<Entry>()
    private var miniStream: ByteArray = ByteArray(0)

    data class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        require(data.size >= 512 && (data[0].toInt() and 0xFF) == 0xD0 && (data[1].toInt() and 0xFF) == 0xCF) { "Non è un file OLE2" }
        sectorSize = 1 shl u16(0x1E)
        miniSectorSize = 1 shl u16(0x20)
        val numFat = i32(0x2C)
        val firstDir = i32(0x30)
        miniCutoff = i32(0x38)
        val firstMiniFat = i32(0x3C)
        val numMiniFat = i32(0x40)
        var difatSector = i32(0x44)
        val fatSectors = mutableListOf<Int>()
        for (k in 0 until 109) { val s = i32(0x4C + k * 4); if (s >= 0) fatSectors += s }
        var guard = 0
        while (difatSector >= 0 && fatSectors.size < numFat && guard++ < 10000) {
            val off = offset(difatSector)
            val per = sectorSize / 4 - 1
            for (k in 0 until per) { val s = i32(off + k * 4); if (s >= 0) fatSectors += s }
            difatSector = i32(off + per * 4)
        }
        val fatList = ArrayList<Int>()
        for (s in fatSectors.take(numFat)) {
            val off = offset(s)
            for (k in 0 until sectorSize / 4) fatList += if (off + k * 4 + 4 <= data.size) i32(off + k * 4) else -1
        }
        fat = fatList.toIntArray()
        val miniFatBytes = if (numMiniFat > 0 && firstMiniFat >= 0) readChain(firstMiniFat, Long.MAX_VALUE) else ByteArray(0)
        val mb = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
        miniFat = IntArray(miniFatBytes.size / 4) { mb.getInt(it * 4) }
        val dir = readChain(firstDir, Long.MAX_VALUE)
        val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until dir.size / 128) {
            val base = k * 128
            val nameLen = (db.getShort(base + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
            val name = String(dir, base, maxOf(0, nameLen - 2), Charsets.UTF_16LE)
            val type = dir[base + 0x42].toInt()
            val start = db.getInt(base + 0x74)
            val size = db.getInt(base + 0x78).toLong() and 0xFFFFFFFFL
            entries += Entry(name, type, start, size)
        }
        entries.firstOrNull { it.type == 5 }?.let { root -> if (root.start >= 0) miniStream = readChain(root.start, root.size) }
    }

    private fun u16(off: Int) = bb.getShort(off).toInt() and 0xFFFF
    private fun i32(off: Int) = bb.getInt(off)
    private fun offset(sector: Int) = (sector + 1) * sectorSize

    private fun readChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && guard++ < 2_000_000) {
            val off = offset(s)
            if (off >= data.size) break
            out.write(data, off, minOf(sectorSize, data.size - off))
            if (out.size() >= size) break
            s = if (s < fat.size) fat[s] else -2
        }
        val b = out.toByteArray()
        return if (size < b.size) b.copyOf(size.toInt()) else b
    }

    private fun readMiniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && guard++ < 1_000_000) {
            val off = s * miniSectorSize
            if (off >= miniStream.size) break
            out.write(miniStream, off, minOf(miniSectorSize, miniStream.size - off))
            if (out.size() >= size) break
            s = if (s < miniFat.size) miniFat[s] else -2
        }
        val b = out.toByteArray()
        return if (size < b.size) b.copyOf(size.toInt()) else b
    }

    fun names(): List<String> = entries.filter { it.type == 2 }.map { it.name }

    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        return if (e.size < miniCutoff) readMiniChain(e.start, e.size) else readChain(e.start, e.size)
    }
}

/** Lettori per i formati binari Office 97-2003. */
object LegacyParsers {

    private val cp1252: Charset = Charset.forName("windows-1252")

    // ------------------------------------------------------------------ XLS (BIFF8)

    fun parseXls(name: String, bytes: ByteArray): LoadedDocument {
        val cf = CompoundFile(bytes)
        val wb = cf.stream("Workbook") ?: cf.stream("Book") ?: error("File Excel 97-2003 non valido")
        val bb = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        data class Rec(val type: Int, val pos: Int, val data: ByteArray)
        val recs = mutableListOf<Rec>()
        var p = 0
        while (p + 4 <= wb.size) {
            val type = bb.getShort(p).toInt() and 0xFFFF
            val len = bb.getShort(p + 2).toInt() and 0xFFFF
            if (p + 4 + len > wb.size) break
            recs += Rec(type, p, wb.copyOfRange(p + 4, p + 4 + len))
            p += 4 + len
        }
        val sheetsByPos = LinkedHashMap<Int, String>()
        val sst = mutableListOf<String>()
        var i = 0
        while (i < recs.size) {
            val r = recs[i]
            when (r.type) {
                0x0085 -> { // BOUNDSHEET
                    val rb = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
                    val pos = rb.getInt(0)
                    val hidden = r.data[4].toInt() and 0x03
                    val sheetType = r.data[5].toInt() and 0xFF
                    val cch = r.data[6].toInt() and 0xFF
                    val high = r.data[7].toInt() and 1
                    val nm = if (high == 1) String(r.data, 8, minOf(cch * 2, r.data.size - 8), Charsets.UTF_16LE)
                    else String(r.data, 8, minOf(cch, r.data.size - 8), cp1252)
                    if (sheetType == 0 && hidden == 0) sheetsByPos[pos] = nm
                }
                0x00FC -> { // SST + CONTINUE
                    val segments = mutableListOf(r.data)
                    var j = i + 1
                    while (j < recs.size && recs[j].type == 0x003C) { segments += recs[j].data; j++ }
                    sst += readSst(segments)
                    i = j - 1
                }
            }
            i++
        }
        val tables = mutableListOf<DocTable>()
        var current: String? = null
        var cells = sortedMapOf<Int, MutableMap<Int, String>>()
        var pendingFormula: Pair<Int, Int>? = null
        fun put(row: Int, col: Int, v: String) { if (v.isNotEmpty()) cells.getOrPut(row) { sortedMapOf() }[col] = v }
        fun flush() {
            val name0 = current ?: return
            val grid = OfficeParsers.toGrid(cells)
            if (grid.isNotEmpty()) tables += DocTable(name0, grid)
            cells = sortedMapOf()
        }
        for (r in recs) {
            val rb = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
            when (r.type) {
                0x0809 -> { // BOF
                    val sheetName = sheetsByPos[r.pos]
                    if (sheetName != null) { flush(); current = sheetName }
                }
                0x000A -> { flush(); current = null } // EOF
                0x00FD -> if (current != null && r.data.size >= 10) put(rb.getShort(0).u(), rb.getShort(2).u(), sst.getOrElse(rb.getInt(6)) { "" })
                0x0203 -> if (current != null && r.data.size >= 14) put(rb.getShort(0).u(), rb.getShort(2).u(), OfficeParsers.formatNumber(rb.getDouble(6).toString()))
                0x027E -> if (current != null && r.data.size >= 10) put(rb.getShort(0).u(), rb.getShort(2).u(), OfficeParsers.formatNumber(rk(rb.getInt(6)).toString()))
                0x00BD -> if (current != null && r.data.size >= 6) { // MULRK
                    val row = rb.getShort(0).u()
                    val first = rb.getShort(2).u()
                    val n = (r.data.size - 6) / 6
                    for (k in 0 until n) put(row, first + k, OfficeParsers.formatNumber(rk(rb.getInt(4 + k * 6 + 2)).toString()))
                }
                0x0204 -> if (current != null && r.data.size >= 9) { // LABEL
                    val cch = rb.getShort(6).u()
                    val high = r.data[8].toInt() and 1
                    val s = if (high == 1) String(r.data, 9, minOf(cch * 2, r.data.size - 9), Charsets.UTF_16LE) else String(r.data, 9, minOf(cch, r.data.size - 9), cp1252)
                    put(rb.getShort(0).u(), rb.getShort(2).u(), s)
                }
                0x0006 -> if (current != null && r.data.size >= 14) { // FORMULA
                    val row = rb.getShort(0).u(); val col = rb.getShort(2).u()
                    val isSpecial = (r.data[12].toInt() and 0xFF) == 0xFF && (r.data[13].toInt() and 0xFF) == 0xFF
                    if (!isSpecial) put(row, col, OfficeParsers.formatNumber(rb.getDouble(6).toString()))
                    else when (r.data[6].toInt()) {
                        0 -> pendingFormula = row to col
                        1 -> put(row, col, if (r.data[8].toInt() != 0) "VERO" else "FALSO")
                    }
                }
                0x0207 -> pendingFormula?.let { (row, col) -> // STRING (risultato formula)
                    if (r.data.size >= 3) {
                        val cch = rb.getShort(0).u(); val high = r.data[2].toInt() and 1
                        put(row, col, if (high == 1) String(r.data, 3, minOf(cch * 2, r.data.size - 3), Charsets.UTF_16LE) else String(r.data, 3, minOf(cch, r.data.size - 3), cp1252))
                    }
                    pendingFormula = null
                }
            }
        }
        flush()
        val pages = tables.mapIndexed { idx, t ->
            PageText(name, idx + 1, "[Foglio: ${t.name}]\n" + t.rows.joinToString("\n") { it.joinToString(" | ").trimEnd(' ', '|') })
        }.ifEmpty { listOf(PageText(name, 1, "")) }
        return LoadedDocument(name, OfficeParsers.Kind.XLS.label, pages, tables)
    }

    private fun Short.u() = toInt() and 0xFFFF

    private fun rk(v: Int): Double {
        val d = if (v and 2 != 0) (v shr 2).toDouble()
        else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 1 != 0) d / 100.0 else d
    }

    /** Legge la Shared String Table gestendo le stringhe spezzate tra record CONTINUE. */
    private fun readSst(segments: List<ByteArray>): List<String> {
        var seg = 0
        var pos = 8
        val out = mutableListOf<String>()
        val total = ByteBuffer.wrap(segments[0]).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        fun avail() = segments[seg].size - pos
        fun next(): Boolean { seg++; pos = 0; return seg < segments.size }
        fun byte(): Int { while (avail() <= 0) if (!next()) throw IndexOutOfBoundsException(); return segments[seg][pos++].toInt() and 0xFF }
        fun u16() = byte() or (byte() shl 8)
        fun i32() = u16() or (u16() shl 16)
        fun skip(n: Int) { var k = n; while (k > 0) { if (avail() <= 0 && !next()) return; val t = minOf(k, avail()); pos += t; k -= t } }
        try {
            while (out.size < total && seg < segments.size) {
                val cch = u16()
                val flags = byte()
                var high = flags and 1
                val rich = if (flags and 8 != 0) u16() else 0
                val ext = if (flags and 4 != 0) i32() else 0
                val sb = StringBuilder()
                var remaining = cch
                while (remaining > 0) {
                    if (avail() <= 0) { if (!next()) break; high = byte() and 1 }
                    val bytesPer = if (high == 1) 2 else 1
                    val n = minOf(remaining, avail() / bytesPer)
                    if (n <= 0) { if (!next()) break; high = byte() and 1; continue }
                    val s = segments[seg]
                    if (high == 1) sb.append(String(s, pos, n * 2, Charsets.UTF_16LE)) else sb.append(String(s, pos, n, cp1252))
                    pos += n * bytesPer
                    remaining -= n
                }
                skip(rich * 4)
                skip(ext)
                out += sb.toString()
            }
        } catch (_: IndexOutOfBoundsException) { }
        return out
    }

    // ------------------------------------------------------------------ DOC (Word 97-2003)

    fun parseDoc(name: String, bytes: ByteArray): LoadedDocument {
        val notes = mutableListOf<String>()
        val text = try {
            docText(bytes)
        } catch (e: Exception) {
            notes += "Estrazione approssimata (struttura del file non standard)."
            heuristicText(bytes)
        }
        return LoadedDocument(name, OfficeParsers.Kind.DOC.label, OfficeParsers.splitPages(name, text), notes = notes)
    }

    private fun docText(bytes: ByteArray): String {
        val cf = CompoundFile(bytes)
        val wd = cf.stream("WordDocument") ?: error("WordDocument mancante")
        val w = ByteBuffer.wrap(wd).order(ByteOrder.LITTLE_ENDIAN)
        require((w.getShort(0).toInt() and 0xFFFF) == 0xA5EC) { "FIB non valido" }
        val flags = w.getShort(0x0A).toInt() and 0xFFFF
        if (flags and 0x0100 != 0) error("Documento protetto da password")
        val table = cf.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: error("Tabella mancante")
        val ccpText = w.getInt(0x4C)
        val fcClx = w.getInt(0x01A2)
        val lcbClx = w.getInt(0x01A6)
        require(lcbClx > 0 && fcClx >= 0 && fcClx + lcbClx <= table.size) { "Clx non valido" }
        val t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        var p = fcClx
        while (p < fcClx + lcbClx && table[p].toInt() == 0x01) { p += 3 + (t.getShort(p + 1).toInt() and 0xFFFF) }
        require(table[p].toInt() == 0x02) { "Pcdt non trovato" }
        val lcb = t.getInt(p + 1)
        val plc = p + 5
        val n = (lcb - 4) / 12
        val sb = StringBuilder()
        for (k in 0 until n) {
            val cpStart = t.getInt(plc + k * 4)
            val cpEnd = t.getInt(plc + (k + 1) * 4)
            val pcd = plc + (n + 1) * 4 + k * 8
            val fcRaw = t.getInt(pcd + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val fc = fcRaw and 0x3FFFFFFF
            val len = cpEnd - cpStart
            if (len <= 0) continue
            if (compressed) {
                val off = fc / 2
                if (off + len > wd.size) continue
                sb.append(String(wd, off, len, cp1252))
            } else {
                if (fc + len * 2 > wd.size) continue
                sb.append(String(wd, fc, len * 2, Charsets.UTF_16LE))
            }
        }
        val raw = if (ccpText in 1..sb.length) sb.substring(0, ccpText) else sb.toString()
        return cleanWordText(raw)
    }

    /** Pulisce caratteri di controllo Word: campi, celle di tabella, interruzioni. */
    fun cleanWordText(raw: String): String {
        val out = StringBuilder()
        var fieldDepth = 0
        val showResult = ArrayDeque<Boolean>()
        for (c in raw) {
            when (c.code) {
                0x13 -> { fieldDepth++; showResult.addLast(false) }
                0x14 -> { if (showResult.isNotEmpty()) { showResult.removeLast(); showResult.addLast(true) } }
                0x15 -> { if (fieldDepth > 0) fieldDepth--; if (showResult.isNotEmpty()) showResult.removeLast() }
                else -> {
                    if (showResult.isNotEmpty() && !showResult.last()) continue
                    when (c.code) {
                        0x0D, 0x0B, 0x0C -> out.append('\n')
                        // 0x07 = fine cella; due di fila = fine riga della tabella
                        0x07 -> if (out.endsWith(" | ")) { out.setLength(out.length - 3); out.append('\n') } else out.append(" | ")
                        0x09 -> out.append('\t')
                        0x1E -> out.append('-')
                        0x1F -> {}
                        0xA0 -> out.append(' ')
                        else -> if (c.code >= 0x20) out.append(c)
                    }
                }
            }
        }
        return out.toString().lines().joinToString("\n") { it.trimEnd().removeSuffix(" |").removeSuffix("|").trimEnd() }
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** Ultima risorsa: estrae sequenze leggibili (UTF-16 o cp1252) dal file. */
    fun heuristicText(bytes: ByteArray): String {
        val source = try { CompoundFile(bytes).stream("WordDocument") ?: bytes } catch (_: Exception) { bytes }
        fun runs(s: String) = Regex("[\\p{L}\\p{N}\\p{P}\\p{Zs}€\\n\\r\\t]{6,}").findAll(s).map { it.value.trim() }.filter { r -> r.count { it.isLetter() } >= r.length / 2 }.toList()
        val u16 = runs(String(source, Charsets.UTF_16LE))
        val b8 = runs(String(source, cp1252))
        val best = if (u16.sumOf { it.length } > b8.sumOf { it.length }) u16 else b8
        return best.joinToString("\n").replace('\r', '\n')
    }
}
