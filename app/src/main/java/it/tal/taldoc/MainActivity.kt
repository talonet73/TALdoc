package it.tal.taldoc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val teal = Color(0xFF007C80)
private val burgundy = Color(0xFF8C2945)
private val ochre = Color(0xFFD4A52A)

data class PageText(val document: String, val page: Int, val text: String)

enum class QueryMode { SEARCH, TRANSCRIPTION, SUMMARY, QUESTION }

data class ScoredPassage(val page: PageText, val text: String, val score: Int)

private val italianStopWords = setOf(
 "a", "ad", "al", "alla", "alle", "allo", "ai", "agli", "che", "chi", "ci", "con", "come", "cosa",
 "da", "dal", "dalla", "dalle", "dallo", "dei", "del", "della", "delle", "dello", "di", "e", "ed",
 "è", "era", "essere", "fa", "fai", "fammi", "il", "in", "io", "la", "le", "li", "lo", "ma", "mi",
 "ne", "non", "o", "per", "più", "quale", "quali", "quando", "quanto", "que", "questa", "questo",
 "se", "sei", "si", "su", "sul", "sulla", "ti", "tu", "un", "una", "uno", "dammi", "dimmi", "trova",
 "cerca", "qual", "sono", "ha", "hanno", "ce", "l", "d", "dell", "all", "nell",
 "dice", "dire", "significa", "vuol", "dire", "che", "cosa", "che", "il", "la", "di", "del", "della",
 "codice", "cod", "numero", "n"
)

class MainActivity : ComponentActivity() {
 private val pages = mutableStateListOf<PageText>()
 private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
 private val sessionDir by lazy { File(cacheDir, "session").apply { mkdirs() } }
 private fun clearSession() { sessionDir.deleteRecursively(); pages.clear() }

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  clearSession()
  setContent {
   val scope = rememberCoroutineScope()
   var ready by remember { mutableStateOf(false) }
   var progress by remember { mutableFloatStateOf(0f) }
   var busy by remember { mutableStateOf(false) }
   var status by remember { mutableStateOf("Pronto per acquisire documenti") }
   var query by remember { mutableStateOf("") }
   var answer by remember { mutableStateOf("") }
   var mode by remember { mutableStateOf(QueryMode.SEARCH) }
   var cameraUri by remember { mutableStateOf<Uri?>(null) }

   val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    scope.launch {
     busy = true
     for (uri in uris) {
      try { importDocument(uri) { status = it } }
      catch (e: Exception) { status = "Errore importazione: ${e.message}" }
     }
     busy = false
     if (uris.isNotEmpty()) status = "Analizzate ${pages.size} pagine"
    }
   }
   val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
    val uri = cameraUri
    if (ok && uri != null) scope.launch {
     busy = true
     try { importDocument(uri) { status = it } }
     catch (e: Exception) { status = "Errore foto: ${e.message}" }
     busy = false
    }
   }
   val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (!granted) status = "Permesso fotocamera negato. Necessario per scattare foto."
    else status = "Permesso concesso. Premi di nuovo Fotocamera."
   }
   val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    if (uri != null) scope.launch { try { exportPdf(uri, query, answer); status = "PDF esportato" } catch (e: Exception) { status = "Errore export: ${e.message}" } }
   }
   val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
    if (uri != null) scope.launch { try { exportCsv(uri); status = "CSV esportato" } catch (e: Exception) { status = "Errore export: ${e.message}" } }
   }

   LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) { sessionDir.mkdirs() }
    progress = 0.4f
    withContext(Dispatchers.IO) { recognizer }
    progress = 0.8f
    withContext(Dispatchers.IO) { sessionDir.listFiles() }
    progress = 1f
    delay(180)
    ready = true
   }

   MaterialTheme(colorScheme = lightColorScheme(primary = teal, secondary = burgundy, tertiary = ochre)) {
    if (!ready) {
     Column(Modifier.fillMaxSize().background(teal).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
      Icon(Icons.Default.Search, null, Modifier.size(120.dp), tint = Color.White)
      Text("TALdoc AI", fontSize = 38.sp, color = Color.White, fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(48.dp))
      LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = ochre, trackColor = Color.White.copy(alpha = .3f))
      Text("${(progress * 100).toInt()}%", color = Color.White)
     }
    } else {
     Column(Modifier.fillMaxSize().background(Color(0xFFF8FAFA)).padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
       Icon(Icons.Default.Search, "Logo TALdoc", tint = teal, modifier = Modifier.size(38.dp))
       Text("TALdoc AI", fontSize = 24.sp, color = teal, fontWeight = FontWeight.Bold)
      }
      Spacer(Modifier.height(12.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
       Button(onClick = { importer.launch(arrayOf("application/pdf", "image/*")) }, enabled = !busy) { Text("Importa PDF / foto") }
       OutlinedButton(onClick = {
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
         requestCameraPermission.launch(Manifest.permission.CAMERA)
         return@OutlinedButton
        }
        try {
         if (!sessionDir.exists() && !sessionDir.mkdirs()) error("Impossibile creare cartella foto")
         val file = File.createTempFile("foto-", ".jpg", sessionDir)
         val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", file)
         cameraUri = uri
         camera.launch(uri)
        } catch (e: Exception) {
         status = "Fotocamera non disponibile: ${e.javaClass.simpleName}: ${e.message}"
         android.util.Log.e("TALdocCamera", "Impossibile avviare fotocamera", e)
        }
       }, enabled = !busy) { Text("Fotocamera") }
      }
      Text(status, color = burgundy, fontSize = 13.sp)
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      Text("Documenti: ${pages.map { it.document }.distinct().size} · Pagine: ${pages.size}", fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(8.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
       QueryMode.entries.forEach { m ->
        FilterChip(
         selected = mode == m,
         onClick = { mode = m },
         label = {
          Text(when (m) {
           QueryMode.SEARCH -> "Cerca"
           QueryMode.TRANSCRIPTION -> "Trascrizione"
           QueryMode.SUMMARY -> "Riassunto"
           QueryMode.QUESTION -> "Domanda"
          }, fontSize = 12.sp)
         }
        )
       }
      }
      Spacer(Modifier.height(6.dp))

      OutlinedTextField(
       value = query,
       onValueChange = { query = it },
       modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 100.dp, max = 160.dp),
       label = { Text("Scrivi frase, domanda o richiesta") },
       placeholder = { Text("Es: dammi l'indirizzo di SSI FACTORY, qual è la paga base...") },
       minLines = 3,
       maxLines = 6
      )
      Spacer(Modifier.height(6.dp))
      Button(
       onClick = {
        status = "Elaborazione in corso..."
        answer = processQuery(pages.toList(), query, mode)
        status = "Elaborazione completata"
       },
       enabled = !busy && pages.isNotEmpty()
      ) { Text("Elabora") }

      if (answer.isNotBlank()) {
       Spacer(Modifier.height(8.dp))
       Card(
        modifier = Modifier
         .fillMaxWidth()
         .heightIn(max = 260.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
       ) {
        Column(
         Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(12.dp)
        ) {
         Text("Risultato", fontWeight = FontWeight.Bold, color = teal, fontSize = 14.sp)
         Spacer(Modifier.height(4.dp))
         Text(answer, fontSize = 13.sp)
        }
       }
      }

      Spacer(Modifier.height(8.dp))
      Text("Elaborazione offline intelligente sui documenti della sessione.", fontSize = 11.sp, color = burgundy)

      Box(Modifier.weight(1f).fillMaxWidth()) {
       Column(Modifier.verticalScroll(rememberScrollState())) {
        pages.forEach { page ->
         Text("${page.document} · Pagina ${page.page}", fontWeight = FontWeight.Bold, color = teal)
         Text(page.text.take(600), fontSize = 13.sp)
         Spacer(Modifier.height(10.dp))
        }
       }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
       OutlinedButton(onClick = { pdfLauncher.launch("TALdoc-risultati.pdf") }, enabled = pages.isNotEmpty()) { Text("PDF") }
       OutlinedButton(onClick = { csvLauncher.launch("TALdoc-dati.csv") }, enabled = pages.isNotEmpty()) { Text("CSV") }
       Button(onClick = { clearSession(); finish() }, colors = ButtonDefaults.buttonColors(containerColor = burgundy)) { Text("Esci e cancella") }
      }
     }
    }
   }
  }
 }

private fun extractKeywords(query: String): List<String> {
 val cleaned = query.lowercase().replace(Regex("[^a-zàèéìòù0-9 ]"), " ")
 val parts = cleaned.split(" ").map { it.trim() }.filter { it.isNotEmpty() }
 val words = parts.filter { (it.length >= 3 && it !in italianStopWords) || it.matches(Regex("\\d+")) }
 val entities = extractEntities(query).map { it.lowercase() }
 return (words + entities).distinct()
}

private fun extractCodes(query: String): List<String> {
 return Regex("""\b(\d{3,6})\b""").findAll(query).map { it.value }.distinct().toList()
}

private fun splitIntoChunks(text: String): List<String> {
 val lines = text.lines().map { it.trim() }.filter { it.length >= 8 }
 val sentences = text.replace("\n", ". ").split(". ", "! ", "? ")
  .map { it.trim() }.filter { it.length > 15 }
 return (lines + sentences).distinct()
}

private fun detectIntent(query: String): String {
 val q = query.lowercase()
 return when {
  q.contains("indirizzo") || q.contains("via") || q.contains("dove") || q.contains("sede") ||
   q.contains("abitazione") || q.contains("residenza") || q.contains("corso") || q.contains("piazza") -> "ADDRESS"
  q.contains("importo") || q.contains("paga") || q.contains("stipendio") || q.contains("totale") ||
   q.contains("netto") || q.contains("lordo") || q.contains("euro") || q.contains("€") || q.contains("quanto") -> "AMOUNT"
  q.contains("data") || q.contains("quando") || q.contains("mese") || q.contains("giorno") || q.contains("anno") -> "DATE"
  q.contains("nome") || q.contains("chi") || q.contains("dipendente") || q.contains("persona") -> "NAME"
  else -> "GENERAL"
 }
}

private fun extractEntities(query: String): List<String> {
 val pattern = Regex("""\b([A-ZÀÈÉÌÒÙ]{2,}(?:\s+[A-ZÀÈÉÌÒÙ]{2,})*|[A-ZÀÈÉÌÒÙ][a-zàèéìòù]+(?:\s+[A-ZÀÈÉÌÒÙ][a-zàèéìòù]+)*)\b""")
 return pattern.findAll(query).map { it.value.trim() }.filter { it.length >= 3 }.distinct().toList()
}

private fun isAddressLike(text: String): Boolean {
 val lower = text.lowercase()
 return lower.contains("via ") || lower.contains("viale ") || lower.contains("corso ") ||
  lower.contains("piazza ") || lower.contains("largo ") || Regex("""\b\d{5}\b""").containsMatchIn(text) ||
  (Regex("""\b\d{1,4}[a-z]?\b""").containsMatchIn(text) && (lower.contains("via") || lower.contains("n.")))
}

private fun isAmountLike(text: String): Boolean {
 return Regex("""[€€]?\s*\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})?""").containsMatchIn(text) ||
  text.contains("€") || text.lowercase().contains("euro")
}

private fun processQuery(pages: List<PageText>, rawQuery: String, mode: QueryMode): String {
 val q = rawQuery.trim()
 if (pages.isEmpty()) return "Nessun documento caricato."

 return try {
  when (mode) {
   QueryMode.SEARCH -> {
    if (q.isBlank()) return "Inserisci una parola o una frase da cercare."
    val matches = pages.filter { it.text.contains(q, ignoreCase = true) }
    if (matches.isEmpty()) "Nessun risultato trovato per \"$q\"."
    else matches.joinToString("\n\n") { "${it.document}, pagina ${it.page}:\n${it.text.take(800)}" }
   }
   QueryMode.TRANSCRIPTION -> {
    val filtered = if (q.isBlank()) pages else pages.filter {
     it.text.contains(q, ignoreCase = true) || it.document.contains(q, ignoreCase = true)
    }
    if (filtered.isEmpty()) "Nessuna pagina corrisponde al filtro."
    else filtered.joinToString("\n\n---\n\n") { "### ${it.document} · Pagina ${it.page}\n${it.text}" }
   }
   QueryMode.SUMMARY -> {
    val full = pages.joinToString("\n") { it.text }
    if (full.isBlank()) return "Nessun testo da riassumere."
    val sentences = splitIntoChunks(full)
    if (sentences.isEmpty()) {
     "Riassunto (testo breve):\n\n" + full.take(1200)
    } else {
     val keywords = extractKeywords(q)
     val scored = sentences.map { s ->
      val lower = s.lowercase()
      val hits = keywords.count { lower.contains(it) }
      s to (hits * 50 + s.length)
     }.sortedByDescending { it.second }
      .take(7)
      .map { it.first }
     "Riassunto estrattivo:\n\n" + scored.joinToString("\n\n") { "• $it." }
    }
   }
   QueryMode.QUESTION -> {
    if (q.isBlank()) return "Scrivi la domanda."
    val keywords = extractKeywords(q)
    val entities = extractEntities(q)
    val intent = detectIntent(q)
    val codes = extractCodes(q)

    // Special handling for code lookups (e.g. "codice 8002", "cosa dice 8002")
    if (codes.isNotEmpty()) {
     val codeResults = mutableListOf<String>()
     for (page in pages) {
      val lines = page.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
      for (code in codes) {
       val matching = lines.filter { it.contains(code) }
       matching.forEach { line ->
        codeResults.add("${page.document} · pagina ${page.page}:\n$line")
       }
      }
     }
     if (codeResults.isNotEmpty()) {
      return "Codice ${codes.joinToString(", ")}:\n\n" + codeResults.distinct().take(5).joinToString("\n\n")
     }
    }

    if (keywords.isEmpty() && entities.isEmpty()) return "Non ho capito le parole chiave della domanda. Prova a riformularla."

    val candidates = mutableListOf<ScoredPassage>()
    for (page in pages) {
     val chunks = splitIntoChunks(page.text)
     for (chunk in chunks) {
      val lower = chunk.lowercase()
      var score = keywords.count { lower.contains(it) } * 12
      entities.forEach { ent ->
       if (lower.contains(ent.lowercase())) score += 25
      }
      when (intent) {
       "ADDRESS" -> if (isAddressLike(chunk)) score += 30
       "AMOUNT" -> if (isAmountLike(chunk)) score += 28
       "DATE" -> if (Regex("""\b\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}\b""").containsMatchIn(chunk)) score += 20
       else -> {}
      }
      if (score > 0) {
       candidates.add(ScoredPassage(page, chunk, score))
      }
     }
    }

    val best = candidates.sortedByDescending { it.score }.distinctBy { it.text }.take(6)
    if (best.isEmpty()) {
     "Non ho trovato passaggi rilevanti per: ${(keywords + entities).distinct().joinToString(", ")}.\n\nProva con parole più specifiche presenti nel documento."
    } else {
     val precise = when (intent) {
      "ADDRESS" -> {
       val addrChunks = best.filter { isAddressLike(it.text) }
       if (addrChunks.isNotEmpty()) {
        val top = addrChunks.first()
        "Indirizzo trovato:\n${top.text}\n\n(dal documento ${top.page.document}, pagina ${top.page.page})"
       } else null
      }
      "AMOUNT" -> {
       val amtChunks = best.filter { isAmountLike(it.text) }
       if (amtChunks.isNotEmpty()) {
        val top = amtChunks.first()
        "Importo/valore trovato:\n${top.text}\n\n(dal documento ${top.page.document}, pagina ${top.page.page})"
       } else null
      }
      else -> null
     }

     val answerLines = best.take(4).joinToString("\n\n") { p ->
      "${p.page.document} · pagina ${p.page.page}:\n${p.text}"
     }
     val header = if (precise != null) precise + "\n\n---\nAltri passaggi rilevanti:\n\n" else "Risposta basata sui documenti (intento: $intent, chiavi: ${(keywords + entities).distinct().joinToString(", ")}):\n\n"
     header + answerLines
    }
   }
  }
 } catch (e: Exception) {
  "Errore durante l'elaborazione: ${e.message}"
 }
}

 private suspend fun importDocument(uri: Uri, onStatus: (String) -> Unit) {
  val mime = contentResolver.getType(uri) ?: ""
  val name = uri.lastPathSegment?.substringAfterLast('/')?.take(60) ?: "Documento"
  if (mime == "application/pdf" || name.endsWith(".pdf", true)) {
   val file = withContext(Dispatchers.IO) {
    File(sessionDir, "pdf-${UUID.randomUUID()}.pdf").also { target ->
     contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
      ?: error("Impossibile aprire PDF")
    }
   }
   withContext(Dispatchers.IO) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
     PdfRenderer(fd).use { renderer ->
      for (i in 0 until renderer.pageCount) {
       onStatus("OCR ${i + 1}/${renderer.pageCount}")
       renderer.openPage(i).use { p ->
        val scale = minOf(2f, 2400f / maxOf(p.width, p.height))
        val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(AndroidColor.WHITE)
        try {
         p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
         val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
         withContext(Dispatchers.Main) { pages.add(PageText(name, i + 1, result.text)) }
        } finally { bmp.recycle() }
       }
      }
     }
    }
   }
  } else {
   val bitmap = withContext(Dispatchers.IO) {
    contentResolver.openInputStream(uri)?.use { stream ->
     val bytes = stream.readBytes()
     val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
     BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
     val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 2400)
     BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Immagine non valida")
   }
   try {
    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
    pages.add(PageText(name, 1, result.text))
   } finally { bitmap.recycle() }
  }
 }

 private suspend fun exportCsv(uri: Uri) = withContext(Dispatchers.IO) {
  contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
   out.appendLine("documento,pagina,testo")
   pages.forEach { p ->
    fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    out.appendLine("${csv(p.document)},${p.page},${csv(p.text)}")
   }
  } ?: error("Destinazione non disponibile")
 }

 private suspend fun exportPdf(uri: Uri, question: String, response: String) = withContext(Dispatchers.IO) {
  val pdf = PdfDocument()
  try {
   val lines = (listOf("TALdoc AI", "Richiesta: $question", "Risultato: $response") + pages.flatMap { p -> listOf("${p.document} - pagina ${p.page}") + p.text.lines() }).flatMap { line -> line.chunked(85).ifEmpty { listOf("") } }
   var index = 0
   var number = 1
   while (index < lines.size) {
    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, number++).create())
    val paint = android.graphics.Paint().apply { color = AndroidColor.BLACK; textSize = 11f }
    var y = 40f
    repeat(54) {
     if (index < lines.size) { page.canvas.drawText(lines[index++], 30f, y, paint); y += 14f }
    }
    pdf.finishPage(page)
   }
   contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) } ?: error("Destinazione non disponibile")
  } finally { pdf.close() }
 }

 override fun onDestroy() {
  recognizer.close()
  super.onDestroy()
 }
}
