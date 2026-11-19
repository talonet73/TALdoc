package it.tal.taldoc.core

import it.tal.taldoc.core.OfficeParsers.Kind

/** Smista i formati non grafici (tutto tranne PDF e immagini, gestiti con OCR su Android). */
object DocumentParsers {

    fun parse(name: String, kind: Kind, bytes: ByteArray): LoadedDocument = when (kind) {
        Kind.DOCX -> OfficeParsers.parseDocx(name, bytes)
        Kind.XLSX -> OfficeParsers.parseXlsx(name, bytes)
        Kind.ODT -> OfficeParsers.parseOdf(name, bytes, spreadsheet = false)
        Kind.ODS -> OfficeParsers.parseOdf(name, bytes, spreadsheet = true)
        Kind.DOC -> LegacyParsers.parseDoc(name, bytes)
        Kind.XLS -> LegacyParsers.parseXls(name, bytes)
        Kind.RTF -> OfficeParsers.parseRtf(name, bytes)
        Kind.CSV -> OfficeParsers.parseCsv(name, bytes)
        Kind.TEXT -> OfficeParsers.parseText(name, bytes)
        Kind.UNKNOWN -> {
            val text = OfficeParsers.decodeText(bytes)
            val printable = text.count { it.isLetterOrDigit() || it.isWhitespace() }.toDouble() / text.length.coerceAtLeast(1)
            if (printable > 0.85) OfficeParsers.parseText(name, bytes)
            else error("Formato non supportato")
        }
        Kind.PDF, Kind.IMAGE -> error("PDF e immagini vanno letti con il motore OCR")
    }
}
