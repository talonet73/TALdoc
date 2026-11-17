package it.tal.taldoc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    if (!granted) {
     status = "Permesso fotocamera negato. Necessario per scattare foto."
    } else {
     status = "Permesso concesso. Premi di nuovo Fotocamera."
    }
   }
   val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    if (uri != null) scope.launch { try { exportPdf(uri, query, answer); status = "PDF esportato" } catch (e: Exception) { status = "Errore export: ${e.message}" } }
   }
   val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
    if (uri != null) scope.launch { try { exportCsv(uri); status = "CSV esportato" } catch (e: Exception) { status = "Errore export: ${e.message}" } }
   }
   LaunchedEffect(Unit) {
    // Progress is driven by actual initialization checkpoints, not a simulated timer.
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
      Spacer(Modifier.height(16.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
       Button(onClick = { importer.launch(arrayOf("application/pdf", "image/*")) }, enabled = !busy) { Text("Importa PDF / foto") }
       OutlinedButton(onClick = {
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
         requestCameraPermission.launch(Manifest.permission.CAMERA)
         return@OutlinedButton
        }
        try {
         // Recover session directory after the one-shot cleanup.
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
      OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Cerca nei documenti") }, placeholder = { Text("Parola, numero, frase...") })
      Button(onClick = {
       val matches = pages.filter { it.text.contains(query.trim(), ignoreCase = true) }
       answer = if (query.isBlank()) "Inserisci una ricerca." else if (matches.isEmpty()) "Nessun risultato trovato." else matches.joinToString("\n\n") { "${it.document}, pagina ${it.page}: " + it.text.take(550) }
      }, enabled = !busy) { Text("Cerca") }
      Text("Ricerca testuale offline. Chat AI non ancora disponibile.", fontSize = 12.sp, color = burgundy)
      Box(Modifier.weight(1f).fillMaxWidth()) {
       Column(Modifier.verticalScroll(rememberScrollState())) {
        if (answer.isNotBlank()) Text(answer)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        pages.forEach { page ->
         Text("${page.document} · Pagina ${page.page}", fontWeight = FontWeight.Bold, color = teal)
         Text(page.text.take(700), fontSize = 13.sp)
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
   val lines = (listOf("TALdoc AI", "Ricerca: $question", "Risultato: $response") + pages.flatMap { p -> listOf("${p.document} - pagina ${p.page}") + p.text.lines() }).flatMap { line -> line.chunked(85).ifEmpty { listOf("") } }
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
