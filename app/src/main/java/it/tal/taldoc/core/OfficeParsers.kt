package it.tal.taldoc.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** Lettori per formati testuali e Office (senza dipendenze esterne). */
object OfficeParsers {

    enum class Kind(val label: String) {
        PDF("PDF"), DOCX("Word"), DOC("Word 97-2003"), XLSX("Excel"), XLS("Excel 97-2003"), ODT("OpenDocument testo"),
        ODS("OpenDocument foglio"), RTF("RTF"), CSV("CSV"), TEXT("Testo"), IMAGE("Immagine"), UNKNOWN("Sconosciuto")
    }

    fun detect(name: String, mime: String?, head: ByteArray): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = mime.orEmpty().lowercase()
        val zip = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        val ole = head.size >= 8 && (head[0].toInt() and 0xFF) == 0xD0 && (head[1].toInt() and 0xFF) == 0xCF
        val pdf = head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "%PDF"
        return when {
            pdf || ext == "pdf" || m == "application/pdf" -> Kind.PDF
            ext == "docx" || ext == "docm" || m.contains("wordprocessingml") -> Kind.DOCX
            ext == "xlsx" || ext == "xlsm" || m.contains("spreadsheetml") -> Kind.XLSX
            ext == "odt" || m.contains("opendocument.text") -> Kind.ODT
            ext == "ods" || m.contains("opendocument.spreadsheet") -> Kind.ODS
            ole && (ext == "xls" || m.contains("ms-excel")) -> Kind.XLS
            ole && (ext == "doc" || m.contains("msword")) -> Kind.DOC
            ole -> if (ext == "xls") Kind.XLS else Kind.DOC
            ext == "doc" -> if (String(head, 0, minOf(5, head.size), Charsets.ISO_8859_1) == "{\\rtf") Kind.RTF else Kind.DOC
            ext == "xls" -> Kind.XLS
            ext == "rtf" || m.contains("rtf") || (head.size > 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "{\\rtf") -> Kind.RTF
            ext == "csv" || ext == "tsv" || m.contains("csv") || m.contains("tab-separated") -> Kind.CSV
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "gif") -> Kind.IMAGE
            zip -> sniffZip(head)
            ext in setOf("txt", "md", "json", "xml", "html", "htm", "log", "ini", "yaml", "yml") || m.startsWith("text/") -> Kind.TEXT
            else -> Kind.UNKNOWN
        }
    }

    private fun sniffZip(head: ByteArray): Kind {
        val s = String(head, Charsets.ISO_8859_1)
        return when {
            s.contains("word/") -> Kind.DOCX
            s.contains("xl/") -> Kind.XLSX
            s.contains("opendocument.text") -> Kind.ODT
            s.contains("opendocument.spreadsheet") -> Kind.ODS
            else -> Kind.DOCX
        }
    }

    // ------------------------------------------------------------------ testo semplice

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    fun parseText(name: String, bytes: ByteArray): LoadedDocument {
        var text = decodeText(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "html" || ext == "htm" || text.trimStart().startsWith("<!DOCTYPE html", true) || text.trimStart().startsWith("<html", true)) {
            text = stripHtml(text)
        }
        return LoadedDocument(name, Kind.TEXT.label, splitPages(name, text))
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</h\\d>|</li>"), "\n")
        .replace(Regex("(?i)</t[dh]>"), " | ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
        .replace(Regex("\\n{3,}"), "\n\n")

    /** Divide un testo lungo in "pagine" di circa 3000 caratteri (o sui salti pagina \f). */
    fun splitPages(name: String, text: String, size: Int = 3000): List<PageText> {
        val byFormFeed = text.split('\u000C')
        val pages = mutableListOf<String>()
        for (part in byFormFeed) {
            if (part.length <= size * 1.3) { pages += part; continue }
            var cur = StringBuilder()
            for (line in part.lines()) {
                if (cur.length + line.length > size && cur.isNotEmpty()) { pages += cur.toString(); cur = StringBuilder() }
                cur.append(line).append('\n')
            }
            if (cur.isNotEmpty()) pages += cur.toString()
        }
        val cleaned = pages.map { it.trim('\n') }.filter { it.isNotBlank() }.ifEmpty { listOf("") }
        return cleaned.mapIndexed { i, t -> PageText(name, i + 1, t) }
    }

    fun parseCsv(name: String, bytes: ByteArray): LoadedDocument {
        val text = decodeText(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val firstLines = text.lines().take(5).joinToString("\n")
        val delim = listOf(';', '\t', ',', '|').maxByOrNull { d -> firstLines.count { it == d } } ?: ','
        val rows = parseDelimited(text, delim)
        val table = DocTable(name, rows)
        val pageText = rows.joinToString("\n") { r -> r.joinToString(" | ").trimEnd(' ', '|') }
        return LoadedDocument(name, Kind.CSV.label, splitPages(name, pageText), listOf(table))
    }

    fun parseDelimited(text: String, delim: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' && (cell.isEmpty() || quoted) -> quoted = !quoted
                !quoted && ch == delim -> { row += cell.toString().trim(); cell.clear() }
                !quoted && ch == '\n' -> { row += cell.toString().trim(); cell.clear(); if (row.any { it.isNotEmpty() }) rows += row; row = mutableListOf() }
                else -> cell.append(ch)
            }
            i++
        }
        row += cell.toString().trim()
        if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }

    // ------------------------------------------------------------------ ZIP + XML

    fun unzip(bytes: ByteArray, accept: (String) -> Boolean): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory && accept(e.name)) out[e.name] = zin.readBytes()
            }
        }
        return out
    }

    private fun sax(bytes: ByteArray, handler: DefaultHandler) {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = false
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
        f.newSAXParser().parse(InputSource(ByteArrayInputStream(bytes)), handler)
    }

    // ------------------------------------------------------------------ DOCX

    fun parseDocx(name: String, bytes: ByteArray): LoadedDocument {
        val files = unzip(bytes) { it.startsWith("word/") && it.endsWith(".xml") }
        val main = files["word/document.xml"] ?: error("File Word non valido (manca word/document.xml)")
        val h = DocxHandler()
        sax(main, h)
        h.flushPage()
        val pages = h.pages.mapIndexed { i, t -> PageText(name, i + 1, t.trim()) }.filter { it.text.isNotEmpty() }
            .ifEmpty { listOf(PageText(name, 1, "")) }
        val extra = StringBuilder()
        for ((path, data) in files) {
            if (path.matches(Regex("word/(header|footer|footnotes|endnotes)\\d*\\.xml"))) {
                val hh = DocxHandler(); sax(data, hh); hh.flushPage()
                val t = hh.pages.joinToString("\n").trim()
                if (t.isNotEmpty()) extra.append(t).append('\n')
            }
        }
        val allPages = if (extra.isNotBlank()) {
            pages + PageText(name, pages.size + 1, "[Intestazioni, piè di pagina e note]\n" + extra.toString().lines().distinct().joinToString("\n"))
        } else pages
        // ripartisce pagine troppo lunghe (documenti senza interruzioni di pagina)
        val split = allPages.flatMap { p -> if (p.text.length > 4500) splitPages(name, p.text).map { it.text } else listOf(p.text) }
            .mapIndexed { i, t -> PageText(name, i + 1, t) }
        return LoadedDocument(name, Kind.DOCX.label, split, h.tables.mapIndexed { i, rows -> DocTable("Tabella ${i + 1}", rows) })
    }

    private class DocxHandler : DefaultHandler() {
        val pages = mutableListOf<String>()
        val tables = mutableListOf<List<List<String>>>()
        private val page = StringBuilder()
        private val para = StringBuilder()
        private var inText = false
        private var tableDepth = 0
        private var rows = mutableListOf<List<String>>()
        private var cells = mutableListOf<String>()
        private val cell = StringBuilder()

        fun flushPage() { if (para.isNotEmpty()) endPara(); pages += page.toString(); page.clear() }

        private fun endPara() {
            if (tableDepth > 0) { if (cell.isNotEmpty()) cell.append(' '); cell.append(para.toString().trim()) }
            else page.append(para.toString().trimEnd()).append('\n')
            para.clear()
        }

        override fun startElement(uri: String?, localName: String?, qName: String, atts: Attributes) {
            when (qName) {
                "w:t", "w:delText" -> inText = qName == "w:t"
                "w:tab" -> para.append('\t')
                "w:br", "w:cr" -> if (atts.getValue("w:type") == "page") { endPara(); pages += page.toString(); page.clear() } else para.append('\n')
                "w:lastRenderedPageBreak" -> if (tableDepth == 0 && page.length > 200) { endPara(); pages += page.toString(); page.clear() }
                "w:tbl" -> { if (tableDepth == 0) rows = mutableListOf(); tableDepth++ }
                "w:tr" -> if (tableDepth == 1) cells = mutableListOf()
                "w:tc" -> if (tableDepth == 1) cell.clear()
                "w:noBreakHyphen" -> para.append('-')
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "w:t" -> inText = false
                "w:p" -> endPara()
                "w:tc" -> if (tableDepth == 1) { cells += cell.toString().trim(); cell.clear() }
                "w:tr" -> if (tableDepth == 1) { rows += cells.toList(); page.append(cells.joinToString(" | ")).append('\n') }
                "w:tbl" -> { tableDepth--; if (tableDepth == 0) { tables += rows.toList(); page.append('\n') } }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) para.append(ch, start, length)
        }
    }

    // ------------------------------------------------------------------ XLSX

    fun parseXlsx(name: String, bytes: ByteArray): LoadedDocument {
        val files = unzip(bytes) { it.startsWith("xl/") && (it.endsWith(".xml") || it.endsWith(".rels")) }
        val shared = mutableListOf<String>()
        files["xl/sharedStrings.xml"]?.let { data ->
            sax(data, object : DefaultHandler() {
                val sb = StringBuilder(); var inT = false; var inRPh = false
                override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                    when (q) { "si" -> sb.clear(); "t" -> inT = true; "rPh" -> inRPh = true }
                }
                override fun endElement(u: String?, l: String?, q: String) {
                    when (q) { "si" -> shared += sb.toString(); "t" -> inT = false; "rPh" -> inRPh = false }
                }
                override fun characters(ch: CharArray, s: Int, len: Int) { if (inT && !inRPh) sb.append(ch, s, len) }
            })
        }
        // nomi dei fogli
        val rels = HashMap<String, String>()
        files["xl/_rels/workbook.xml.rels"]?.let { data ->
            sax(data, object : DefaultHandler() {
                override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                    if (q == "Relationship") {
                        val target = a.getValue("Target") ?: return
                        val t = target.removePrefix("/").let { if (it.startsWith("xl/")) it else "xl/$it" }
                        rels[a.getValue("Id")] = t
                    }
                }
            })
        }
        val sheets = mutableListOf<Pair<String, String>>()
        files["xl/workbook.xml"]?.let { data ->
            sax(data, object : DefaultHandler() {
                override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                    if (q == "sheet") {
                        val id = a.getValue("r:id") ?: return
                        rels[id]?.let { sheets += (a.getValue("name") ?: "Foglio") to it }
                    }
                }
            })
        }
        if (sheets.isEmpty()) {
            files.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sorted()
                .forEachIndexed { i, p -> sheets += "Foglio ${i + 1}" to p }
        }
        val tables = mutableListOf<DocTable>()
        val pages = mutableListOf<PageText>()
        sheets.forEachIndexed { idx, (sheetName, path) ->
            val data = files[path] ?: return@forEachIndexed
            val rows = parseSheet(data, shared)
            if (rows.isEmpty()) return@forEachIndexed
            tables += DocTable(sheetName, rows)
            val text = "[Foglio: $sheetName]\n" + rows.joinToString("\n") { r -> r.joinToString(" | ").trimEnd(' ', '|') }
            pages += PageText(name, idx + 1, text)
        }
        val renumbered = pages.mapIndexed { i, p -> p.copy(page = i + 1) }.ifEmpty { listOf(PageText(name, 1, "")) }
        return LoadedDocument(name, Kind.XLSX.label, renumbered, tables)
    }

    fun columnIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (!c.isLetter()) break; n = n * 26 + (c.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    private fun parseSheet(data: ByteArray, shared: List<String>): List<List<String>> {
        val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        sax(data, object : DefaultHandler() {
            var rowIdx = -1; var col = 0; var type = ""; val v = StringBuilder(); var inV = false; var inIs = false
            var autoCol = 0
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (q) {
                    "row" -> { rowIdx = (a.getValue("r")?.toIntOrNull() ?: (rowIdx + 2)) - 1; autoCol = 0 }
                    "c" -> {
                        col = a.getValue("r")?.let { columnIndex(it) } ?: autoCol
                        autoCol = col + 1
                        type = a.getValue("t") ?: ""; v.clear()
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> if (inIs) inV = true
                }
            }
            override fun endElement(u: String?, l: String?, q: String) {
                when (q) {
                    "v" -> inV = false
                    "t" -> if (inIs) inV = false
                    "is" -> inIs = false
                    "c" -> {
                        val raw = v.toString()
                        val value = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw == "1") "VERO" else "FALSO"
                            else -> formatNumber(raw)
                        }
                        if (value.isNotEmpty() && rowIdx >= 0) rows.getOrPut(rowIdx) { sortedMapOf() }[col] = value
                    }
                }
            }
            override fun characters(ch: CharArray, s: Int, len: Int) { if (inV) v.append(ch, s, len) }
        })
        return toGrid(rows)
    }

    fun toGrid(rows: Map<Int, Map<Int, String>>): List<List<String>> {
        if (rows.isEmpty()) return emptyList()
        val maxCol = minOf(rows.values.maxOf { it.keys.maxOrNull() ?: 0 }, 200)
        return rows.values.map { r -> (0..maxCol).map { r[it] ?: "" }.dropLastWhile { it.isEmpty() } }
            .filter { it.isNotEmpty() }
    }

    /** "1234.5" -> "1234.5", "3.0000000000000004" -> "3" (pulizia arrotondamenti binari). */
    fun formatNumber(raw: String): String {
        val d = raw.trim().toDoubleOrNull() ?: return raw.trim()
        if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        return java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    // ------------------------------------------------------------------ ODT / ODS

    fun parseOdf(name: String, bytes: ByteArray, spreadsheet: Boolean): LoadedDocument {
        val content = unzip(bytes) { it == "content.xml" }["content.xml"] ?: error("File OpenDocument non valido")
        val sb = StringBuilder()
        val tables = mutableListOf<DocTable>()
        sax(content, object : DefaultHandler() {
            var tableDepth = 0; var tableName = ""; var rows = mutableListOf<List<String>>(); var cells = mutableListOf<String>()
            val cell = StringBuilder(); var repeat = 1; var rowRepeat = 1; var cellValue: String? = null; var inP = 0
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (q) {
                    "table:table" -> { tableDepth++; if (tableDepth == 1) { tableName = a.getValue("table:name") ?: "Tabella"; rows = mutableListOf() } }
                    "table:table-row" -> { cells = mutableListOf(); rowRepeat = (a.getValue("table:number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtMost(3) }
                    "table:table-cell", "table:covered-table-cell" -> {
                        cell.clear(); repeat = (a.getValue("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(60)
                        cellValue = a.getValue("office:value")
                    }
                    "text:p", "text:h" -> { inP++; if (tableDepth > 0 && cell.isNotEmpty()) cell.append(' ') }
                    "text:s" -> { val c = a.getValue("text:c")?.toIntOrNull() ?: 1; target().append(" ".repeat(c.coerceAtMost(20))) }
                    "text:tab" -> target().append('\t')
                    "text:line-break" -> target().append('\n')
                }
            }
            fun target(): StringBuilder = if (tableDepth > 0) cell else sb
            override fun endElement(u: String?, l: String?, q: String) {
                when (q) {
                    "text:p", "text:h" -> { inP--; if (tableDepth == 0) sb.append('\n') }
                    "table:table-cell", "table:covered-table-cell" -> {
                        val text = cell.toString().trim().ifEmpty { cellValue?.let { formatNumber(it) } ?: "" }
                        repeat(repeat) { cells += text }
                    }
                    "table:table-row" -> {
                        val trimmed = cells.dropLastWhile { it.isEmpty() }
                        if (trimmed.isNotEmpty()) repeat(rowRepeat) { rows += trimmed; sb.append(trimmed.joinToString(" | ")).append('\n') }
                    }
                    "table:table" -> { tableDepth--; if (tableDepth == 0) { if (rows.isNotEmpty()) tables += DocTable(tableName, rows.toList()); sb.append('\n') } }
                }
            }
            override fun characters(ch: CharArray, s: Int, len: Int) { if (inP > 0) target().append(ch, s, len) }
        })
        val kind = if (spreadsheet) Kind.ODS else Kind.ODT
        return LoadedDocument(name, kind.label, splitPages(name, sb.toString()), tables)
    }

    // ------------------------------------------------------------------ RTF

    fun parseRtf(name: String, bytes: ByteArray): LoadedDocument {
        val src = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder()
        val cp1252 = Charset.forName("windows-1252")
        var i = 0
        var depth = 0
        var skipDepth = -1
        var ucSkip = 1
        var pendingSkip = 0
        val skipDest = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl", "rsidtbl", "generator", "listtable", "listoverridetable", "object", "fldinst")
        while (i < src.length) {
            val c = src[i]
            when {
                c == '{' -> { depth++; i++ }
                c == '}' -> { if (depth == skipDepth) skipDepth = -1; depth--; i++ }
                c == '\\' && i + 1 < src.length -> {
                    val n = src[i + 1]
                    if (n == '\'' && i + 3 < src.length) {
                        val hex = src.substring(i + 2, i + 4)
                        if (skipDepth < 0) {
                            if (pendingSkip > 0) pendingSkip-- else hex.toIntOrNull(16)?.let { out.append(String(byteArrayOf(it.toByte()), cp1252)) }
                        }
                        i += 4
                    } else if (n.isLetter()) {
                        var j = i + 1
                        while (j < src.length && src[j].isLetter()) j++
                        val word = src.substring(i + 1, j)
                        var k = j
                        if (k < src.length && (src[k] == '-' || src[k].isDigit())) { k++; while (k < src.length && src[k].isDigit()) k++ }
                        val param = src.substring(j, k).toIntOrNull()
                        if (k < src.length && src[k] == ' ') k++
                        if (skipDepth < 0) {
                            when (word) {
                                in skipDest -> skipDepth = depth
                                "par", "line", "row", "sect", "page" -> out.append('\n')
                                "tab" -> out.append('\t')
                                "cell" -> out.append(" | ")
                                "uc" -> ucSkip = param ?: 1
                                "u" -> { param?.let { out.append((if (it < 0) it + 65536 else it).toChar()) }; pendingSkip = ucSkip }
                                "emdash" -> out.append('—'); "endash" -> out.append('–'); "bullet" -> out.append('•')
                                "lquote" -> out.append('‘'); "rquote" -> out.append('’'); "ldblquote" -> out.append('“'); "rdblquote" -> out.append('”')
                            }
                        }
                        i = k
                    } else {
                        if (n == '*') skipDepth = depth
                        else if (skipDepth < 0 && (n == '\\' || n == '{' || n == '}')) out.append(n)
                        else if (skipDepth < 0 && n == '~') out.append(' ')
                        i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> { if (skipDepth < 0) { if (pendingSkip > 0) pendingSkip-- else out.append(c) }; i++ }
            }
        }
        val text = out.toString().lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\\n{3,}"), "\n\n")
        return LoadedDocument(name, Kind.RTF.label, splitPages(name, text))
    }
}
