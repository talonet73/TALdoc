package it.tal.taldoc.io

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import it.tal.taldoc.core.LoadedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Esportazione dei risultati in PDF, TXT e CSV. */
object Exporter {

    suspend fun pdf(context: Context, uri: Uri, title: String, body: String) = withContext(Dispatchers.IO) {
        val pdf = PdfDocument()
        try {
            val pageW = 595; val pageH = 842; val margin = 40f
            val text = Paint().apply { color = Color.BLACK; textSize = 10.5f; isAntiAlias = true }
            val head = Paint().apply { color = Color.rgb(0, 124, 128); textSize = 16f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
            val maxW = pageW - 2 * margin
            val lines = mutableListOf<String>()
            for (raw in body.lines()) {
                if (raw.isEmpty()) { lines += ""; continue }
                var rest = raw
                while (rest.isNotEmpty()) {
                    var n = text.breakText(rest, true, maxW, null).coerceAtLeast(1)
                    if (n < rest.length) {
                        val sp = rest.lastIndexOf(' ', n)
                        if (sp > n / 2) n = sp + 1
                    }
                    lines += rest.substring(0, n).trimEnd()
                    rest = rest.substring(n)
                }
            }
            var idx = 0
            var num = 1
            val lineH = 14f
            while (idx < lines.size || num == 1) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, num).create())
                var y = margin + 6
                if (num == 1) { page.canvas.drawText(title, margin, y + 10, head); y += 34 }
                while (idx < lines.size && y < pageH - margin) {
                    page.canvas.drawText(lines[idx++], margin, y, text); y += lineH
                }
                val foot = Paint().apply { color = Color.GRAY; textSize = 8f }
                page.canvas.drawText("TALdoc AI · pagina $num", margin, pageH - 20f, foot)
                pdf.finishPage(page)
                num++
                if (idx >= lines.size) break
            }
            context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) } ?: error("Destinazione non disponibile")
        } finally { pdf.close() }
    }

    suspend fun text(context: Context, uri: Uri, body: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(body) } ?: error("Destinazione non disponibile")
    }

    suspend fun csv(context: Context, uri: Uri, docs: List<LoadedDocument>) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { out ->
            out.write("﻿")
            out.appendLine("documento;pagina;testo")
            fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            docs.forEach { d -> d.pages.forEach { p -> out.appendLine("${q(d.name)};${p.page};${q(p.text)}") } }
        } ?: error("Destinazione non disponibile")
    }
}
