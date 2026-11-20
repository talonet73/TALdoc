@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.tal.taldoc

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import it.tal.taldoc.core.Mode
import java.io.File

// Stile Windows XP (Luna)
private val XpTitleBlue = Color(0xFF003399)
private val XpHighlight = Color(0xFF316AC5)
private val XpBackground = Color(0xFFECE9D8)
private val XpButtonFace = Color(0xFFD4D0C8)
private val XpText = Color(0xFF000000)
private val Teal = XpTitleBlue      // alias per compatibilità
private val Burgundy = Color(0xFF8B0000)
private val Ochre = Color(0xFFFFCC00)
private val Paper = XpBackground

private const val MODEL_PAGE = "https://huggingface.co/litert-community/Gemma3-1B-IT"

private val importMimes = arrayOf(
    "application/pdf", "image/*", "text/*",
    "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
    "application/rtf", "text/csv", "application/octet-stream",
)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Burgundy, tertiary = Ochre, background = Paper)) {
                App(vm, onExit = { vm.clearSession(); finishAndRemoveTask() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** File aperti o condivisi da altre app (Gmail, File, WhatsApp…). */
    private fun handleIncoming(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
}

@Composable
private fun App(vm: MainViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    var showModel by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importUris(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) vm.importScans(listOf(u), "Foto")
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera(context) { uri -> cameraUri = uri; camera.launch(uri) }
        else Toast.makeText(context, "Permesso fotocamera negato", Toast.LENGTH_LONG).show()
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val uris = r?.pages?.map { it.imageUri }.orEmpty()
            vm.importScans(uris, "Scansione")
        }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModel(uri) }
    val pdfOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let(vm::exportPdf) }
    val txtOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::exportTxt) }
    val csvOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::exportCsv) }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera(context) { uri -> cameraUri = uri; camera.launch(uri) }
        } else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun openScanner() {
        val activity = context as? Activity ?: return openCamera()
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanner.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                Toast.makeText(context, "Scanner non disponibile, uso la fotocamera", Toast.LENGTH_SHORT).show()
                openCamera()
            }
    }

    Scaffold(
        containerColor = XpBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DocumentScanner, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("TALdoc AI", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                actions = {
                    AiChip(vm) { showModel = true }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Esporta PDF") }, enabled = vm.messages.isNotEmpty() || vm.docs.isNotEmpty(),
                            onClick = { menu = false; pdfOut.launch("TALdoc-risultati.pdf") })
                        DropdownMenuItem(text = { Text("Esporta testo (TXT)") }, enabled = vm.messages.isNotEmpty() || vm.docs.isNotEmpty(),
                            onClick = { menu = false; txtOut.launch("TALdoc-risultati.txt") })
                        DropdownMenuItem(text = { Text("Esporta CSV (testo per pagina)") }, enabled = vm.docs.isNotEmpty(),
                            onClick = { menu = false; csvOut.launch("TALdoc-dati.csv") })
                        DropdownMenuItem(text = { Text("Modello AI…") }, onClick = { menu = false; showModel = true })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Nuova sessione") }, onClick = { menu = false; vm.clearSession() })
                        DropdownMenuItem(text = { Text("Esci e cancella", color = Burgundy) }, onClick = { menu = false; onExit() })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = XpTitleBlue,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        ) {
            DocumentsBar(vm, onImport = { importer.launch(importMimes) }, onScan = ::openScanner, onPhoto = ::openCamera)
            ModeBar(vm)
            if ((vm.mode == Mode.COMPARE || vm.mode == Mode.MISSING) && vm.docs.size >= 2) ComparePicker(vm)
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ochre)
            }
            Text(vm.status, fontSize = 12.sp, color = Burgundy, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            ChatList(vm, Modifier.weight(1f))
            InputBar(vm, input, onInput = { input = it }, onSend = {
                vm.submit(input); input = ""
            })
        }
    }

    if (showModel) ModelDialog(vm, onDismiss = { showModel = false }, onPick = { modelPicker.launch(arrayOf("*/*")) }, onOpenPage = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_PAGE))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, MODEL_PAGE, Toast.LENGTH_LONG).show() }
    })
}

private fun launchCamera(context: Context, launch: (Uri) -> Unit) {
    try {
        val dir = File(context.cacheDir, "session").apply { mkdirs() }
        val file = File.createTempFile("foto-", ".jpg", dir)
        launch(FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
    } catch (e: Exception) {
        Toast.makeText(context, "Fotocamera non disponibile: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun AiChip(vm: MainViewModel, onClick: () -> Unit) {
    val (label, color) = when (vm.aiStatus) {
        AiStatus.READY -> (if (vm.aiEnabled) "Gemma ✓" else "AI spenta") to Teal
        AiStatus.LOADING, AiStatus.COPYING -> "AI…" to Ochre
        AiStatus.ERROR -> "AI ✗" to Burgundy
        AiStatus.NONE -> "AI offline" to Color.Gray
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, tint = color, modifier = Modifier.size(16.dp)) },
    )
}

@Composable
private fun DocumentsBar(vm: MainViewModel, onImport: () -> Unit, onScan: () -> Unit, onPhoto: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(XpBackground).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImport, enabled = !vm.busy, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Importa")
            }
            OutlinedButton(onClick = onScan, enabled = !vm.busy, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.DocumentScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Scansiona")
            }
            OutlinedButton(onClick = onPhoto, enabled = !vm.busy, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Foto")
            }
        }
        if (vm.docs.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                itemsIndexed(vm.docs, key = { _, d -> d.name }) { i, d ->
                    InputChip(
                        selected = false,
                        onClick = { vm.mode = Mode.TRANSCRIPTION; vm.submit(d.name, Mode.TRANSCRIPTION) },
                        label = { Text("${d.name} · ${d.pages.size}p", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(16.dp)) },
                        trailingIcon = { Icon(Icons.Default.Close, "Rimuovi", Modifier.size(16.dp).clickable { vm.removeDoc(i) }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeBar(vm: MainViewModel) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        items(Mode.entries.toList()) { m ->
            FilterChip(
                selected = vm.mode == m,
                onClick = { vm.mode = m },
                label = { Text(m.label, fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Teal, selectedLabelColor = Color.White),
            )
        }
    }
}

@Composable
private fun ComparePicker(vm: MainViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DocPicker("A", vm.compareA, vm, Modifier.weight(1f)) { vm.compareA = it }
        Text("↔", color = Teal, fontWeight = FontWeight.Bold)
        DocPicker("B", vm.compareB, vm, Modifier.weight(1f)) { vm.compareB = it }
    }
}

@Composable
private fun DocPicker(label: String, selected: Int, vm: MainViewModel, modifier: Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("$label: ${vm.docs.getOrNull(selected)?.name ?: "—"}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vm.docs.forEachIndexed { i, d -> DropdownMenuItem(text = { Text(d.name) }, onClick = { onSelect(i); open = false }) }
        }
    }
}

@Composable
private fun ChatList(vm: MainViewModel, modifier: Modifier) {
    val state = rememberLazyListState()
    val last = vm.messages.lastOrNull()
    LaunchedEffect(vm.messages.size, last?.text?.length) {
        if (vm.messages.isNotEmpty()) state.animateScrollToItem(vm.messages.size - 1)
    }
    if (vm.messages.isEmpty()) {
        Welcome(modifier)
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.messages, key = { it.id }) { m -> MessageBubble(m, vm) }
    }
}

@Composable
private fun Welcome(modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Benvenuto in TALdoc AI", fontWeight = FontWeight.Bold, color = Teal, fontSize = 18.sp)
                Text("1. Carica PDF, Word (DOC/DOCX), Excel (XLS/XLSX), TXT, CSV, ODT o RTF, oppure scansiona un foglio con la fotocamera.", fontSize = 14.sp)
                Text("2. Scegli cosa fare: Domanda, Riassunto, Trascrizione, Cerca, Confronta due documenti, Parti mancanti o Verifica conti.", fontSize = 14.sp)
                Text("3. Scrivi la domanda nel box in basso. Se non è chiara, TALdoc ti chiede di precisare.", fontSize = 14.sp)
                Text("Tutto resta sul telefono: nessun dato viene inviato in rete.", fontSize = 13.sp, color = Burgundy)
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, vm: MainViewModel) {
    val context = LocalContext.current
    val bg = when {
        m.fromUser -> Teal
        m.isClarification -> Color(0xFFFFF4D6)
        else -> Color.White
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = if (m.fromUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = if (m.fromUser) 300.dp else 600.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (!m.fromUser) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val tag = when {
                            m.isClarification -> "❓ Serve un chiarimento"
                            m.byAi -> "✨ ${m.mode.label} · Gemma"
                            else -> m.mode.label
                        }
                        Text(tag, fontSize = 11.sp, color = if (m.isClarification) Burgundy else Teal, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        if (!m.streaming && !m.isClarification) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ContentCopy, "Copia", tint = Color.Gray, modifier = Modifier.size(16.dp).clickable { copy(context, m.text) })
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.Default.Share, "Condividi", tint = Color.Gray, modifier = Modifier.size(16.dp).clickable { share(context, m.text) })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                SelectionContainer {
                    Text(m.text, color = if (m.fromUser) Color.White else Color(0xFF1B1B1B), fontSize = 14.sp, lineHeight = 19.sp)
                }
                if (m.streaming) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = Ochre)
                if (m.sources.isNotEmpty()) {
                    Text("Fonti: " + m.sources.joinToString(" · "), fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                }
                if (m.options.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        m.options.forEach { o ->
                            SuggestionChip(onClick = { vm.choose(o) }, label = { Text(o.label, fontSize = 12.sp) }, enabled = !vm.busy)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputBar(vm: MainViewModel, input: String, onInput: (String) -> Unit, onSend: () -> Unit) {
    val placeholder = when (vm.mode) {
        Mode.QUESTION -> "Es: qual è il totale della fattura? dammi l'indirizzo di…"
        Mode.SUMMARY -> "Facoltativo: su cosa concentrare il riassunto"
        Mode.TRANSCRIPTION -> "Facoltativo: nome del documento da trascrivere"
        Mode.SEARCH -> "Parola o frase da cercare"
        Mode.COMPARE -> "Premi Esegui per confrontare A e B"
        Mode.MISSING -> "Premi Esegui per cercare parti mancanti"
        Mode.CALC -> "Premi Esegui per verificare totali e calcoli"
    }
    Surface(color = XpButtonFace, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp),
                placeholder = { Text(placeholder, fontSize = 13.sp) },
                label = { Text(vm.mode.label) },
                minLines = 1,
                maxLines = 5,
            )
            Spacer(Modifier.width(6.dp))
            if (vm.busy) {
                FilledTonalIconButton(onClick = { vm.cancel() }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Stop, "Annulla") }
            } else if (vm.mode.needsText) {
                FilledIconButton(onClick = onSend, modifier = Modifier.size(52.dp), enabled = vm.docs.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Invia")
                }
            } else {
                Button(onClick = onSend, enabled = vm.docs.isNotEmpty(), modifier = Modifier.height(52.dp)) { Text("Esegui") }
            }
        }
    }
}

@Composable
private fun ModelDialog(vm: MainViewModel, onDismiss: () -> Unit, onPick: () -> Unit, onOpenPage: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        title = { Text("Intelligenza artificiale sul telefono") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Senza modello TALdoc usa il motore offline (ricerca intelligente, estrazione dati, confronto, verifica conti). " +
                        "Con Gemma 3 1B (≈530 MB, gira anche su telefoni di fascia media) le risposte e i riassunti diventano discorsivi. " +
                        "Il modello lavora solo sul telefono.",
                    fontSize = 13.sp,
                )
                when (vm.aiStatus) {
                    AiStatus.COPYING -> { Text(vm.aiMessage, fontSize = 13.sp); LinearProgressIndicator(progress = { vm.copyProgress }, modifier = Modifier.fillMaxWidth()) }
                    AiStatus.LOADING -> { Text(vm.aiMessage, fontSize = 13.sp); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    AiStatus.READY -> Text("✓ ${vm.aiMessage}", color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    AiStatus.ERROR -> Text(vm.aiMessage, color = Burgundy, fontSize = 13.sp)
                    AiStatus.NONE -> Text("Nessun modello installato.", fontSize = 13.sp, color = Color.Gray)
                }
                Text("Come installarlo (una volta sola):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("1. «Scarica Gemma» apre la pagina ufficiale: accetta la licenza e scarica gemma3-1b-it-int4.litertlm.\n2. Torna qui e premi «Carica modello» scegliendo il file scaricato.", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenPage) { Text("Scarica Gemma") }
                    Button(onClick = onPick, enabled = vm.aiStatus != AiStatus.COPYING && vm.aiStatus != AiStatus.LOADING) { Text("Carica modello") }
                }
                if (vm.aiStatus == AiStatus.READY || vm.modelName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Usa l'AI nelle risposte", Modifier.weight(1f), fontSize = 13.sp)
                        Switch(checked = vm.aiEnabled, onCheckedChange = vm::setAi)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Accelerazione GPU (sperimentale)", Modifier.weight(1f), fontSize = 13.sp)
                        Switch(checked = vm.useGpu, onCheckedChange = vm::setGpu)
                    }
                    TextButton(onClick = vm::removeModel) { Text("Rimuovi modello", color = Burgundy) }
                }
            }
        },
    )
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("TALdoc", text))
    Toast.makeText(context, "Copiato", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(i, "Condividi risultato"))
}
