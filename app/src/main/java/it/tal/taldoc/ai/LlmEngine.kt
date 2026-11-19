package it.tal.taldoc.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Modello linguistico on-device (Gemma 3 1B o altro modello .litertlm) tramite LiteRT-LM.
 * Tutto gira sul telefono: nessun dato lascia il dispositivo.
 */
class LlmEngine(private val cacheDir: String) {

    private var engine: Engine? = null
    private val lock = Mutex()

    val isReady: Boolean get() = engine != null

    suspend fun load(modelPath: String, useGpu: Boolean) = lock.withLock {
        withContext(Dispatchers.IO) {
            closeInternal()
            val config = EngineConfig(
                modelPath = modelPath,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                cacheDir = cacheDir,
            )
            val e = Engine(config)
            e.initialize()
            engine = e
        }
    }

    /** Genera una risposta; emette il testo accumulato man mano che arriva. */
    fun generate(system: String, prompt: String, temperature: Double = 0.2): Flow<String> = flow {
        lock.withLock {
            val e = engine ?: error("Modello AI non caricato")
            val conversation = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = temperature),
                )
            )
            try {
                var acc = ""
                conversation.sendMessageAsync(prompt).collect { message ->
                    val chunk = message.toString()
                    // alcune versioni emettono il testo cumulativo, altre solo il pezzo nuovo
                    acc = if (acc.isNotEmpty() && chunk.startsWith(acc)) chunk else acc + chunk
                    emit(acc)
                }
            } finally {
                conversation.close()
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun close() = lock.withLock { closeInternal() }

    private fun closeInternal() {
        try { engine?.close() } catch (_: Exception) { }
        engine = null
    }
}
