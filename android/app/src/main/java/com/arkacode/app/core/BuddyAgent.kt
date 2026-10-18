package com.arkacode.app.core

import androidx.compose.runtime.snapshotFlow
import com.arkacode.app.net.ChatItem
import com.arkacode.app.net.OpenCodeClient
import kotlinx.coroutines.flow.first

/**
 * Percakapan Arka Buddy dengan engine opencode.
 *
 * Sesi dibuat sekali lalu dipakai ulang — antar giliran dan antar buka layar —
 * supaya Buddy punya ingatan percakapan dan daftar sesi tidak menumpuk.
 *
 * Gantian dijalankan lewat [SessionRunRegistry] yang juga dipakai ChatScreen,
 * jadi pelacakan selesai, snapshot id sebelum giliran, dan tunggu isi balasan
 * lengkap tidak ditulis ulang di sini.
 */
class BuddyAgent(
    private val client: OpenCodeClient,
    private val runs: SessionRunRegistry,
    private val prefs: SessionUiPrefs,
) {
    private var cachedId: String? = null

    /**
     * Kirim kalimat pengguna, tunggu giliran selesai, kembalikan balasan yang
     * layak dibacakan.
     *
     * Melempar [IllegalStateException] bila engine gagal, tidak menjawab, atau
     * balasannya belum lengkap — pemanggil menampilkannya sebagai pesan.
     */
    suspend fun ask(said: String): String {
        val id = ensureSession()
        val run = runs.run(id)
        // attachNow dulu, lalu snapshot: kalau `messages` masih kosong saat
        // send() mengambil preIds, polling akan menganggap assistant LAMA
        // sebagai balasan giliran ini.
        runs.attachNow(id)
        val before = run.messages.value.mapTo(mutableSetOf()) { it.id }

        if (!runs.send(id, compose(said), model = null)) {
            throw IllegalStateException(run.error.value ?: "engine sedang sibuk")
        }
        snapshotFlow { run.busy.value }.first { !it }
        run.error.value?.takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }

        val reply = replyForTurn(before, run.messages.value)
            ?: throw IllegalStateException("tidak ada balasan yang selesai")
        reply.error?.takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
        return reply.texts.joinToString(" ").trim().ifEmpty {
            throw IllegalStateException("balasan tanpa teks")
        }
    }

    /**
     * Sesi dipakai ulang dari penyimpanan. Disimpan lama supaya ingatan Buddy
     * bertahan antar buka layar, dan tidak membuat sesi baru tiap kali.
     */
    private suspend fun ensureSession(): String {
        cachedId?.let { return it }
        val saved = runCatching { prefs.buddySessionId() }.getOrNull()?.takeIf { it.isNotBlank() }
        // Sesi lama bisa saja sudah hilang (engine di-reset, sesi dihapus),
        // jadi divalidasi dengan satu bacaan kecil sebelum dipakai ulang.
        val valid = saved?.takeIf { s ->
            runCatching { client.listMessages(s, limit = 1) }.isSuccess
        }
        val id = valid ?: client.createSession().id
        cachedId = id
        if (id != valid) runCatching { prefs.setBuddySessionId(id) }
        return id
    }

    /**
     * Gaya diselipkan tiap giliran, bukan sekali saat sesi dibuat: sesi bisa
     * dipakai ulang dari penyimpanan, dan bila arahannya hanya dikirim sekali
     * tiap kali layar dibuka, giliran pertama habis dipakai menyimpan arahan.
     */
    private fun compose(said: String) = buildString {
        appendLine("You are Arka Buddy, the voice assistant inside Arkacode.")
        appendLine("Reply as spoken language: at most 3 sentences, no markdown,")
        appendLine("no lists, no code, and no restating the question.")
        appendLine("Answer in the same language the user wrote in.")
        appendLine()
        append("User said: ")
        append(said.trim())
    }
}

/**
 * Assistant yang menjadi balasan giliran dimulai dengan snapshot [before].
 *
 * Ambil yang TERBARU ber-id di luar snapshot DAN sudah selesai (medan `finish`
 * terminal). Assistant lama maupun yang masih menggantung dari giliran
 * sebelumnya tidak boleh terbaca sebagai balasan — itu yang membuat Buddy
 * mengulang jawaban lama.
 *
 * Fungsi murni supaya bisa dites tanpa server maupun Android.
 */
fun replyForTurn(before: Set<String>, messages: List<ChatItem>): ChatItem.Assistant? =
    messages.filterIsInstance<ChatItem.Assistant>()
        .lastOrNull { it.id !in before && isTerminalFinish(it.finish) }
