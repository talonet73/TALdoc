package it.tal.taldoc.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import it.tal.taldoc.core.DocumentParsers
import it.tal.taldoc.core.LoadedDocument
import it.tal.taldoc.core.OcrLayout
import it.tal.taldoc.core.OcrLine
import it.tal.taldoc.core.OfficeParsers
import it.tal.taldoc.core.OfficeParsers.Kind
import it.tal.taldoc.core.PageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Importa documenti: testo nativo dove possibile, OCR (ML Kit, offline) per scansioni e foto. */
class DocumentImporter(private val context: Context, private val sessionDir: File) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var pdfBoxReady = false

    fun displayName(uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/')?.take(80) ?: "Documento"
    }

    fun size(uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (_: Exception) { }
        return -1
    }

    suspend fun import(uri: Uri, nameOverride: String? = null, onStatus: (String) -> Unit): LoadedDocument = withContext(Dispatchers.IO) {
        val name = nameOverride ?: displayName(uri)
        val mime = context.contentResolver.getType(uri)
        val head = context.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(4096); val n = s.read(buf).coerceAtLeast(0); buf.copyOf(n)
        } ?: error("Impossibile aprire il file")
        when (val kind = OfficeParsers.detect(name, mime, head)) {
            Kind.PDF -> importPdf(uri, name, onStatus)
            Kind.IMAGE -> {
                onStatus("OCR di $name…")
                val text = ocrImage(uri)
                LoadedDocument(name, Kind.IMAGE.label, listOf(PageText(name, 1, text)),
                    notes = if (text.isBlank()) listOf("Nessun testo riconosciuto: prova una foto più nitida e ben illuminata.") else emptyList())
            }
            else -> {
                onStatus("Lettura di $name…")
                val sz = size(uri)
                if (sz > 150L * 1024 * 1024) error("File troppo grande (${sz / 1024 / 1024} MB)")
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Impossibile leggere il file")
                DocumentParsers.parse(name, kind, bytes)
            }
        }
    }

    /** Pagine fotografate/scansionate (anche più di una) come un unico documento. */
    suspend fun importImages(uris: List<Uri>, name: String, onStatus: (String) -> Unit): LoadedDocument = withContext(Dispatchers.IO) {
        val pages = uris.mapIndexed { i, u ->
            onStatus("OCR pagina ${i + 1}/${uris.size}…")
            PageText(name, i + 1, ocrImage(u))
        }
        LoadedDocument(name, "Scansione", pages)
    }

    private suspend fun importPdf(uri: Uri, name: String, onStatus: (String) -> Unit): LoadedDocument {
        val file = File(sessionDir, "pdf-${UUID.randomUUID()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: error("Impossibile aprire il PDF")
        val notes = mutableListOf<String>()
        try {
            // 1. testo nativo del PDF (preciso e veloce)
            val native = try {
                if (!pdfBoxReady) { PDFBoxResourceLoader.init(context.applicationContext); pdfBoxReady = true }
                PDDocument.load(file).use { doc ->
                    val n = doc.numberOfPages
                    (1..n).map { i ->
                        onStatus("Lettura testo pagina $i/$n…")
                        val stripper = PDFTextStripper()
                        stripper.sortByPosition = true
                        stripper.startPage = i
                        stripper.endPage = i
                        stripper.getText(doc)
                    }
                }
            } catch (e: Exception) {
                if (e.javaClass.simpleName.contains("Password")) error("PDF protetto da password")
                null
            }
            // 2. OCR solo per le pagine senza testo (scansioni)
            val pages = mutableListOf<PageText>()
            var ocrCount = 0
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = renderer.pageCount
                    for (i in 0 until count) {
                        val nativeText = native?.getOrNull(i)?.trim().orEmpty()
                        val text = if (nativeText.count { it.isLetterOrDigit() } >= 25) nativeText else {
                            onStatus("OCR pagina ${i + 1}/$count…")
                            ocrCount++
                            renderer.openPage(i).use { p ->
                                val scale = minOf(3f, 2600f / maxOf(p.width, p.height))
                                val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                try {
                                    bmp.eraseColor(Color.WHITE)
                                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    ocrBitmap(bmp)
                                } finally { bmp.recycle() }
                            }
                        }
                        pages += PageText(name, i + 1, text)
                    }
                }
            }
            if (ocrCount > 0) notes += "$ocrCount pagine lette con OCR (scansione): verifica i numeri importanti."
            return LoadedDocument(name, if (ocrCount == pages.size) "PDF scansionato" else "PDF", pages, notes = notes)
        } finally {
            file.delete()
        }
    }

    private suspend fun ocrImage(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Immagine non leggibile")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Formato immagine non supportato")
        val rotation = try {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
        val upright = if (rotation != 0) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                .also { if (it != decoded) decoded.recycle() }
        } else decoded
        try { return ocrBitmap(upright) } finally { upright.recycle() }
    }

    private suspend fun ocrBitmap(bmp: Bitmap): String {
        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val lines = result.textBlocks.flatMap { b -> b.lines }.mapNotNull { l ->
            val r = l.boundingBox ?: return@mapNotNull null
            OcrLine(l.text, r.left, r.top, r.right, r.bottom)
        }
        return if (lines.isEmpty()) result.text else OcrLayout.rebuild(lines)
    }

    fun close() { try { recognizer.close() } catch (_: Exception) { } }
}
