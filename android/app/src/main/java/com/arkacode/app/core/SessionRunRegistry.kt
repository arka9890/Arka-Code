package com.arkacode.app.core

import androidx.compose.runtime.mutableStateOf
import com.arkacode.app.net.ChatItem
import com.arkacode.app.net.ModelRef
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.PermissionReq
import com.arkacode.app.net.QuestionRequest
import com.arkacode.app.net.ServerEvent
import com.arkacode.app.net.ToolUse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Status "turn berjalan" untuk SATU sesi. Dimiliki [SessionRunRegistry] yang
 * di-scope ke layar engine, sehingga status tetap hidup saat layar chat dibuang
 * (mis. pengguna pindah sesi) — turn di server tidak lagi "berhenti" di UI.
 */
class SessionRun {
    val busy = mutableStateOf(false)
    val messages = mutableStateOf<List<ChatItem>>(emptyList())
    val pending = mutableStateOf<List<PermissionReq>>(emptyList())
    val questions = mutableStateOf<List<QuestionRequest>>(emptyList())
    val liveText = mutableStateOf("")
    val liveReasoning = mutableStateOf("")
    val liveTools = mutableStateOf<List<ToolUse>>(emptyList())
    val toolLabel = mutableStateOf<String?>(null)
    val justSent = mutableStateOf<String?>(null)
    val error = mutableStateOf<String?>(null)

    internal var pollJob: Job? = null
    internal var preIds: Set<String> = emptySet()
    internal var attached = false
}

/**
 * Registry run per sesi. Polling hidup selama [scope] (layar engine), bukan
 * selama layar chat, jadi berpindah sesi tidak membuang pelacakan turn yang
 * masih berjalan. Penyelesaian turn dideteksi dari medan `finish` pesan
 * assistant (server v1.19 tidak mengirim event idle).
 */
class SessionRunRegistry(
    private val scope: CoroutineScope,
    private val client: OpenCodeClient,
) {
    private val runs = LinkedHashMap<String, SessionRun>()
    private var eventsJob: Job? = null

    fun run(sessionId: String): SessionRun = runs.getOrPut(sessionId) { SessionRun() }

    /** Muat awal + sambung kembali ke turn yang masih berjalan di server. */
    fun attach(sessionId: String) {
        val r = run(sessionId)
        ensureEvents()
        if (r.attached) return
        r.attached = true
        scope.launch {
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
            runCatching { client.listPermissions(sessionId) }.getOrNull()?.let { r.pending.value = it }
            runCatching { client.listQuestions(sessionId) }.getOrNull()?.let { r.questions.value = it }
            val active = runCatching { client.sessionActive() }.getOrDefault(emptySet())
            if (sessionId in active && !r.busy.value) {
                r.busy.value = true
                startPolling(sessionId)
            }
        }
    }

    fun refresh(sessionId: String) {
        val r = run(sessionId)
        scope.launch {
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
            runCatching { client.listPermissions(sessionId) }.getOrNull()?.let { r.pending.value = it }
            runCatching { client.listQuestions(sessionId) }.getOrNull()?.let { r.questions.value = it }
        }
    }

    /**
     * Satu kolektor SSE untuk semua sesi: mengubah event server (tool, teks
     * streaming, pertanyaan, persetujuan, idle) menjadi update state seketika,
     * melengkapi polling sebagai jaring pengaman.
     */
    private fun ensureEvents() {
        if (eventsJob?.isActive == true) return
        eventsJob = scope.launch {
            // Sambung ulang otomatis bila koneksi SSE putus: tanpa ini streaming
            // berhenti diam-diam dan balasan hanya mengandalkan polling.
            while (isActive) {
                try {
                    client.serverEvents().collect { ev -> applyEvent(ev) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // koneksi SSE putus — coba sambung lagi di bawah
                }
                if (!isActive) break
                delay(SSE_RETRY_MS)
            }
        }
    }

    /** Terapkan satu event SSE ke state run terkait. */
    private fun applyEvent(ev: ServerEvent) {
        val sid = ev.sessionID ?: return
        if (!runs.containsKey(sid)) return
        val r = run(sid)
        when (ev) {
                    is ServerEvent.Tool -> {
                        if (ev.label.isNotBlank()) r.toolLabel.value = ev.label
                        if (ev.status.isNotBlank()) {
                            val key = ev.id.ifBlank { "live-" + ev.label.ifBlank { "tool" } }
                            val cur = r.liveTools.value.toMutableList()
                            val i = cur.indexOfFirst { it.id == key }
                            val name = ev.label.ifBlank { cur.getOrNull(i)?.name ?: "tool" }
                            val entry = ToolUse(key, name, ev.status)
                            if (i >= 0) cur[i] = entry else cur.add(entry)
                            r.liveTools.value = cur
                        }
                    }
                    is ServerEvent.TextDelta -> r.liveText.value += ev.delta
                    is ServerEvent.TextEnded -> if (ev.text.isNotBlank()) r.liveText.value = ev.text
                    is ServerEvent.ReasoningDelta -> r.liveReasoning.value += ev.delta
                    is ServerEvent.Permission -> scope.launch {
                        runCatching { client.listPermissions(sid) }.getOrNull()?.let { r.pending.value = it }
                    }
                    is ServerEvent.Question -> scope.launch {
                        runCatching { client.listQuestions(sid) }.getOrNull()?.let { r.questions.value = it }
                    }
                    is ServerEvent.Status -> if (ev.status == "idle" && r.busy.value) {
                        r.busy.value = false
                        r.justSent.value = null
                        r.toolLabel.value = null
                        r.liveTools.value = emptyList()
                    }
                    is ServerEvent.Error -> r.error.value = ev.message
                    else -> {}
                }
    }

    fun clearError(sessionId: String) {
        run(sessionId).error.value = null
    }

    /** Kirim prompt (+ lampiran). `false` bila kosong & tanpa lampiran, atau sibuk. */
    fun send(
        sessionId: String,
        text: String,
        model: ModelRef?,
        files: List<Attachment> = emptyList(),
    ): Boolean {
        val trimmed = text.trim()
        val r = run(sessionId)
        if ((trimmed.isEmpty() && files.isEmpty()) || r.busy.value) return false
        r.error.value = null
        r.busy.value = true
        r.justSent.value = trimmed.ifBlank { null }
        r.liveText.value = ""
        r.liveReasoning.value = ""
        r.liveTools.value = emptyList()
        r.toolLabel.value = null
        // Snapshot KETAT (semua id, termasuk assistant menggantung): balasan turn
        // ini harus berupa assistant BARU, jangan sampai assistant lama yang belum
        // ber-"finish" dianggap balasan → pollUntilDone langsung break → busy
        // cepat false, echo hilang, padahal turn masih jalan di server.
        r.preIds = r.messages.value.mapTo(mutableSetOf()) { it.id }
        r.pollJob?.cancel()
        r.pollJob = scope.launch {
            try {
                model?.let { m -> runCatching { client.switchModel(sessionId, m.providerID, m.id) } }
                client.sendPrompt(sessionId, trimmed, files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                r.error.value = e.message
                r.busy.value = false
                r.justSent.value = null
                return@launch
            }
            pollUntilDone(sessionId)
        }
        return true
    }

    /** Hentikan turn yang jalan (juga membatalkan polling). */
    fun stop(sessionId: String) {
        val r = run(sessionId)
        r.pollJob?.cancel()
        r.busy.value = false
        r.liveText.value = ""
        r.liveReasoning.value = ""
        r.liveTools.value = emptyList()
        r.toolLabel.value = null
        r.justSent.value = null
        scope.launch {
            runCatching { client.interrupt(sessionId) }
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
        }
    }

    private fun startPolling(sessionId: String) {
        val r = run(sessionId)
        if (r.pollJob?.isActive == true) return
        r.preIds = snapshotPreIds(r.messages.value)
        r.pollJob = scope.launch { pollUntilDone(sessionId) }
    }

    private suspend fun pollUntilDone(sessionId: String) {
        val r = run(sessionId)
        var waited = 0L
        var fetchFails = 0
        var lastErr: String? = null
        try {
            while (true) {
                val loaded = try {
                    client.listMessages(sessionId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e.message
                    null
                }
                if (loaded != null) {
                    r.messages.value = loaded
                    fetchFails = 0
                } else if (++fetchFails >= MAX_FETCH_FAILS) {
                    r.error.value = lastErr ?: "Tidak dapat memuat pesan dari server"
                }
                runCatching { client.listPermissions(sessionId) }.getOrNull()?.let { r.pending.value = it }
                runCatching { client.listQuestions(sessionId) }.getOrNull()?.let { r.questions.value = it }
                val newAssistant = r.messages.value
                    .filterIsInstance<ChatItem.Assistant>()
                    .lastOrNull { it.id !in r.preIds }
                if (newAssistant != null && isTerminal(newAssistant.finish)) {
                    // Tunggu singkat sampai server menuntaskan penyimpanan isi
                    // balasan — menghindari bubble kosong karena pesan tersimpan
                    // belum lengkap tepat saat `finish` muncul.
                    var settle = 0
                    var asst: ChatItem.Assistant = newAssistant
                    while (settle < SETTLE_TRIES && !hasBody(asst)) {
                        delay(SETTLE_MS)
                        runCatching { client.listMessages(sessionId) }.getOrNull()
                            ?.let { r.messages.value = it }
                        asst = r.messages.value
                            .filterIsInstance<ChatItem.Assistant>()
                            .lastOrNull { it.id !in r.preIds } ?: asst
                        settle++
                    }
                    asst.error?.takeIf { it.isNotBlank() }?.let { r.error.value = it }
                    break
                }
                if (waited >= MAX_POLL_MS) break
                delay(POLL_MS)
                waited += POLL_MS
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            r.busy.value = false
            r.justSent.value = null
            r.toolLabel.value = null
            r.liveTools.value = emptyList()
            // liveText sengaja TIDAK dibersihkan di sini: ChatScreen memakainya
            // sebagai cadangan bila pesan tersimpan tak memuat teks balasan.
            runCatching { client.listMessages(sessionId) }.getOrNull()?.let { r.messages.value = it }
        }
    }

    /** Pesan assistant punya isi (teks/reasoning/tool) — bukan bubble kosong. */
    private fun hasBody(item: ChatItem.Assistant?): Boolean =
        item != null &&
            (item.texts.any { it.isNotBlank() } || item.reasonings.isNotEmpty() || item.tools.isNotEmpty())

    /**
     * Kunci id pesan "sebelum turn ini". Assistant terakhir yang belum selesai
     * DIKECUALIKAN agar saat selesai terhitung sebagai pesan baru (penting untuk
     * menyambung kembali turn yang sudah berjalan saat layar dibuka).
     */
    private fun snapshotPreIds(messages: List<ChatItem>): Set<String> {
        val lastAsst = messages.filterIsInstance<ChatItem.Assistant>().lastOrNull()
        val pendingId = if (lastAsst != null && !isTerminal(lastAsst.finish)) lastAsst.id else null
        val ids = messages.mapTo(mutableSetOf()) { it.id }
        if (pendingId != null) ids.remove(pendingId)
        return ids
    }

    /** `tool-calls` = lanjut ke langkah berikutnya, jadi belum selesai. */
    private fun isTerminal(finish: String?): Boolean = finish != null && finish != "tool-calls"

    private companion object {
        const val POLL_MS = 1500L
        const val MAX_POLL_MS = 30 * 60 * 1000L
        const val SSE_RETRY_MS = 2000L
        const val SETTLE_TRIES = 8
        const val SETTLE_MS = 700L
        const val MAX_FETCH_FAILS = 5
    }
}
