package it.tal.taldoc

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.tal.taldoc.ai.LlmEngine
import it.tal.taldoc.core.CalcChecker
import it.tal.taldoc.core.ClarifyOption
import it.tal.taldoc.core.DocCompare
import it.tal.taldoc.core.EngineResult
import it.tal.taldoc.core.LoadedDocument
import it.tal.taldoc.core.MissingParts
import it.tal.taldoc.core.Mode
import it.tal.taldoc.core.Prompts
import it.tal.taldoc.core.QueryEngine
import it.tal.taldoc.core.Summarizer
import it.tal.taldoc.io.DocumentImporter
import it.tal.taldoc.io.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val mode: Mode,
    val options: List<ClarifyOption> = emptyList(),
    val sources: List<String> = emptyList(),
    val streaming: Boolean = false,
    val byAi: Boolean = false,
    val isClarification: Boolean = false,
)

enum class AiStatus { NONE, COPYING, LOADING, READY, ERROR }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("taldoc", Context.MODE_PRIVATE)
    private val sessionDir = File(app.cacheDir, "session").apply { deleteRecursively(); mkdirs() }
    private val importer = DocumentImporter(app, sessionDir)
    private val llm = LlmEngine(app.cacheDir.path)

    val docs = mutableStateListOf<LoadedDocument>()
    val messages = mutableStateListOf<ChatMessage>()
    var mode by mutableStateOf(Mode.QUESTION)
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Carica un documento per iniziare")
        private set
    var compareA by mutableStateOf(0)
    var compareB by mutableStateOf(1)

    var aiStatus by mutableStateOf(AiStatus.NONE)
        private set
    var aiMessage by mutableStateOf("")
        private set
    var copyProgress by mutableFloatStateOf(0f)
        private set
    var aiEnabled by mutableStateOf(prefs.getBoolean("aiEnabled", true))
        private set
    var useGpu by mutableStateOf(prefs.getBoolean("useGpu", false))
        private set
    val modelName: String? get() = prefs.getString("modelPath", null)?.let { File(it).name }

    private var pendingClarification: String? = null
    private var nextId = 1L
    private var job: Job? = null

    init {
        prefs.getString("modelPath", null)?.let { p -> if (File(p).exists()) loadModel(p) }
    }

    // ------------------------------------------------------------------ documenti

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchBusy {
            for (uri in uris) {
                try {
                    val d = importer.import(uri) { s -> status = s }
                    addDoc(d)
                } catch (e: Exception) {
                    post("Non riesco a leggere «${importer.displayName(uri)}»: ${e.message ?: e.javaClass.simpleName}", Mode.QUESTION)
                }
            }
            status = summaryStatus()
        }
    }

    fun importScans(uris: List<Uri>, label: String) {
        if (uris.isEmpty()) return
        launchBusy {
            try {
                val name = "$label ${docs.count { it.kind == "Scansione" } + 1}"
                addDoc(importer.importImages(uris, name) { s -> status = s })
            } catch (e: Exception) {
                post("Errore OCR: ${e.message}", Mode.QUESTION)
            }
            status = summaryStatus()
        }
    }

    private fun addDoc(d0: LoadedDocument) {
        var name = d0.name
        var k = 2
        while (docs.any { it.name == name }) name = "${d0.name} ($k)".also { k++ }
        val d = if (name == d0.name) d0 else d0.copy(name = name, pages = d0.pages.map { it.copy(document = name) })
        docs += d
        if (docs.size == 2) { compareA = 0; compareB = 1 }
        val chars = d.charCount
        val info = "📄 Caricato «${d.name}» (${d.kind}, ${d.pages.size} pag., $chars caratteri)" +
            (if (d.tables.isNotEmpty()) ", ${d.tables.size} tabelle" else "") +
            d.notes.joinToString("") { "\n⚠ $it" } +
            (if (chars == 0) "\n⚠ Nessun testo trovato nel documento." else "")
        post(info, Mode.QUESTION)
    }

    fun removeDoc(index: Int) {
        if (index !in docs.indices) return
        docs.removeAt(index)
        compareA = compareA.coerceIn(0, (docs.size - 1).coerceAtLeast(0))
        compareB = if (docs.size > 1) compareB.coerceIn(0, docs.size - 1).let { if (it == compareA) (compareA + 1) % docs.size else it } else 0
        status = summaryStatus()
    }

    private fun summaryStatus() = if (docs.isEmpty()) "Carica un documento per iniziare"
    else "${docs.size} documenti · ${docs.sumOf { it.pages.size }} pagine"

    fun clearSession() {
        job?.cancel()
        docs.clear(); messages.clear(); pendingClarification = null
        sessionDir.deleteRecursively(); sessionDir.mkdirs()
        status = summaryStatus()
    }

    // ------------------------------------------------------------------ richieste

    fun submit(input: String, requestedMode: Mode = mode) {
        val text = input.trim()
        if (docs.isEmpty()) {
            post("Prima carica un documento: usa «Importa», «Scansiona» o «Foto».", requestedMode); return
        }
        if (requestedMode.needsText && text.isEmpty()) {
            val opts = QueryEngine(docs.toList()).starterOptions()
            post(if (requestedMode == Mode.SEARCH) "Cosa devo cercare?" else "Cosa vuoi sapere dai documenti?", requestedMode, options = opts, clarify = true)
            return
        }
        messages += ChatMessage(nextId++, true, text.ifEmpty { requestedMode.label }, requestedMode)
        val query = if (requestedMode == Mode.QUESTION && pendingClarification != null && text.isNotEmpty()) "${pendingClarification} $text" else text
        pendingClarification = null
        val snapshot = docs.toList()
        launchBusy {
            when (requestedMode) {
                Mode.QUESTION -> answer(query, snapshot)
                Mode.SUMMARY -> summary(snapshot, text)
                Mode.TRANSCRIPTION -> post(compute { QueryEngine(snapshot).transcription(text) }, requestedMode)
                Mode.SEARCH -> post(compute { QueryEngine(snapshot).search(text) }, requestedMode)
                Mode.COMPARE -> compare(snapshot)
                Mode.MISSING -> post(compute { MissingParts.report(snapshot, pair(snapshot)) }, requestedMode)
                Mode.CALC -> post(compute { CalcChecker.report(snapshot) }, requestedMode)
            }
            status = summaryStatus()
        }
    }

    fun choose(option: ClarifyOption) {
        pendingClarification = null
        val m = option.mode ?: Mode.QUESTION
        mode = m
        submit(option.query, m)
    }

    private fun pair(snapshot: List<LoadedDocument>): Pair<LoadedDocument, LoadedDocument>? {
        if (snapshot.size < 2) return null
        val a = snapshot.getOrNull(compareA) ?: snapshot[0]
        val b = snapshot.getOrNull(compareB)?.takeIf { it !== a } ?: snapshot.first { it !== a }
        return a to b
    }

    private suspend fun compare(snapshot: List<LoadedDocument>) {
        val p = pair(snapshot)
        if (p == null) {
            post("Per il confronto servono almeno due documenti. Carica il secondo documento e riprova.", Mode.COMPARE, clarify = true)
            return
        }
        post(compute { DocCompare.report(p.first, p.second) }, Mode.COMPARE)
    }

    private suspend fun answer(query: String, snapshot: List<LoadedDocument>) {
        val engine = QueryEngine(snapshot)
        val det = compute { engine.answer(query) }
        if (det is EngineResult.Clarify) {
            pendingClarification = query
            post(det.question, Mode.QUESTION, options = det.options, clarify = true)
            return
        }
        det as EngineResult.Answer
        if (!aiEnabled || !llm.isReady) {
            post(det.text, Mode.QUESTION, sources = det.sources)
            return
        }
        val passages = compute { engine.retrieve(query, 12) }
        if (passages.isEmpty()) { post(det.text, Mode.QUESTION, sources = det.sources); return }
        val history = conversationHistory()
        val id = post("…", Mode.QUESTION, streaming = true, byAi = true)
        status = "Gemma sta ragionando…"
        try {
            var out = ""
            llm.generate(Prompts.system, Prompts.question(query, passages, history)).collect { acc ->
                out = acc
                update(id) { it.copy(text = acc) }
            }
            val clar = Prompts.parseClarification(out)
            if (clar != null) {
                pendingClarification = query
                update(id) { it.copy(text = clar, streaming = false, isClarification = true, options = engine.starterOptions().take(3)) }
            } else {
                update(id) {
                    it.copy(
                        text = out.trim().ifEmpty { det.text } + "\n\n— Riscontro diretto nel testo —\n" + firstLines(det.text, 4),
                        streaming = false,
                        sources = passages.map { p -> p.ref }.distinct().take(5),
                    )
                }
            }
        } catch (e: Exception) {
            update(id) { it.copy(text = det.text + "\n\n(AI non disponibile: ${e.message})", streaming = false, byAi = false, sources = det.sources) }
        }
    }

    private fun firstLines(s: String, n: Int) = s.lines().filter { it.isNotBlank() }.take(n).joinToString("\n")

    private fun conversationHistory(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until messages.size - 1) {
            val m = messages[i]; val r = messages[i + 1]
            if (m.fromUser && !r.fromUser && m.mode == Mode.QUESTION) out += m.text to r.text
        }
        return out.takeLast(1)
    }

    private suspend fun summary(snapshot: List<LoadedDocument>, focus: String) {
        if (!aiEnabled || !llm.isReady) {
            post(compute { Summarizer.report(snapshot, focus) }, Mode.SUMMARY); return
        }
        for (d in snapshot) {
            val id = post("📄 ${d.name}\n…", Mode.SUMMARY, streaming = true, byAi = true)
            try {
                val chunks = Prompts.chunks(d.fullText)
                val partials = mutableListOf<String>()
                if (chunks.size > 1) {
                    chunks.forEachIndexed { i, c ->
                        status = "Riassunto ${d.name}: parte ${i + 1}/${chunks.size}…"
                        var last = ""
                        llm.generate(Prompts.system, Prompts.summary(d.name, c, focus)).collect { last = it; update(id) { m -> m.copy(text = "📄 ${d.name}\n(parte ${i + 1}/${chunks.size})\n$it") } }
                        partials += last
                    }
                }
                val prompt = if (chunks.size > 1) Prompts.mergeSummaries(d.name, partials) else Prompts.summary(d.name, chunks.firstOrNull().orEmpty(), focus)
                status = "Riassunto di ${d.name}…"
                var final = ""
                llm.generate(Prompts.system, prompt).collect { final = it; update(id) { m -> m.copy(text = "📄 ${d.name}\n$it") } }
                val facts = Summarizer.keyFacts(d)
                update(id) {
                    it.copy(
                        text = "📄 ${d.name}\n${final.trim()}" + if (facts.isNotEmpty()) "\n\nDati chiave (estratti dal testo):\n" + facts.joinToString("\n") { f -> "• $f" } else "",
                        streaming = false,
                    )
                }
            } catch (e: Exception) {
                update(id) { it.copy(text = Summarizer.summarize(d, focus) + "\n\n(AI non disponibile: ${e.message})", streaming = false, byAi = false) }
            }
        }
    }

    // ------------------------------------------------------------------ modello AI

    fun setAi(enabled: Boolean) { aiEnabled = enabled; prefs.edit().putBoolean("aiEnabled", enabled).apply() }

    fun setGpu(enabled: Boolean) {
        useGpu = enabled
        prefs.edit().putBoolean("useGpu", enabled).apply()
        prefs.getString("modelPath", null)?.let { if (File(it).exists()) loadModel(it) }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            val name = importer.displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!name.endsWith(".litertlm", true) && !name.endsWith(".task", true)) {
                aiStatus = AiStatus.ERROR
                aiMessage = "Il file deve essere un modello .litertlm (es. gemma3-1b-it-int4.litertlm)."
                return@launch
            }
            aiStatus = AiStatus.COPYING
            aiMessage = "Copia del modello nell'app…"
            copyProgress = 0f
            try {
                val dir = File(ctx.filesDir, "models").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, name)
                val total = importer.size(uri).coerceAtLeast(1)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(1 shl 20)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                copyProgress = (copied.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    } ?: error("Impossibile aprire il file")
                }
                prefs.edit().putString("modelPath", target.absolutePath).apply()
                loadModel(target.absolutePath)
            } catch (e: Exception) {
                aiStatus = AiStatus.ERROR
                aiMessage = "Copia non riuscita: ${e.message}"
            }
        }
    }

    private fun loadModel(path: String) {
        viewModelScope.launch {
            aiStatus = AiStatus.LOADING
            aiMessage = "Caricamento di ${File(path).name}…"
            try {
                llm.load(path, useGpu)
                aiStatus = AiStatus.READY
                aiMessage = "${File(path).name} pronto (${if (useGpu) "GPU" else "CPU"})"
            } catch (e: Throwable) {
                aiStatus = AiStatus.ERROR
                aiMessage = "Impossibile avviare il modello: ${e.message ?: e.javaClass.simpleName}" +
                    if (useGpu) "\nProva a disattivare la GPU." else ""
            }
        }
    }

    fun removeModel() {
        viewModelScope.launch {
            llm.close()
            File(ctx.filesDir, "models").deleteRecursively()
            prefs.edit().remove("modelPath").apply()
            aiStatus = AiStatus.NONE
            aiMessage = ""
        }
    }

    // ------------------------------------------------------------------ esportazione

    fun exportText(): String = buildString {
        append("TALdoc AI — risultati\n\n")
        messages.forEach { m -> append(if (m.fromUser) "▶ ${m.mode.label}: ${m.text}\n" else "${m.text}\n"); if (m.sources.isNotEmpty()) append("Fonti: ${m.sources.joinToString("; ")}\n"); append('\n') }
        if (docs.isNotEmpty()) {
            append("\n════════ TRASCRIZIONE ════════\n\n")
            append(QueryEngine(docs.toList()).transcription(""))
        }
    }

    fun exportPdf(uri: Uri) = launchBusy { runCatching { Exporter.pdf(ctx, uri, "TALdoc AI — risultati", exportText()) }.report("PDF") }
    fun exportTxt(uri: Uri) = launchBusy { runCatching { Exporter.text(ctx, uri, exportText()) }.report("TXT") }
    fun exportCsv(uri: Uri) = launchBusy { runCatching { Exporter.csv(ctx, uri, docs.toList()) }.report("CSV") }

    private fun Result<Unit>.report(what: String) {
        status = if (isSuccess) "$what esportato" else "Errore esportazione $what: ${exceptionOrNull()?.message}"
    }

    // ------------------------------------------------------------------ util

    private suspend fun <T> compute(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    private fun launchBusy(block: suspend () -> Unit) {
        job = viewModelScope.launch {
            busy = true
            try { block() } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) post("Errore: ${e.message ?: e.javaClass.simpleName}", mode)
            } finally { busy = false }
        }
    }

    fun cancel() { job?.cancel(); busy = false; status = "Operazione annullata" }

    private fun post(
        text: String, mode: Mode, options: List<ClarifyOption> = emptyList(), sources: List<String> = emptyList(),
        streaming: Boolean = false, byAi: Boolean = false, clarify: Boolean = false,
    ): Long {
        val id = nextId++
        messages += ChatMessage(id, false, text, mode, options, sources, streaming, byAi, clarify)
        return id
    }

    private fun update(id: Long, f: (ChatMessage) -> ChatMessage) {
        val i = messages.indexOfLast { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    override fun onCleared() {
        importer.close()
        sessionDir.deleteRecursively()
        super.onCleared()
    }
}
