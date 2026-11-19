package com.arkacode.app.core

import android.content.Context
import com.arkacode.app.net.ChatFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/** Satu pesan asisten → daftar path relatif berkas yang lahir di gilirannya. */
@Serializable
private data class TurnFileRecord(val id: String, val paths: List<String> = emptyList())

@Serializable
private data class TurnFileDoc(val messages: List<TurnFileRecord> = emptyList())

/**
 * Menyimpan daftar berkas hasil giliran per id pesan asisten.
 *
 * Tanpa ini, kartu berkas hanya muncul saat giliran selesai dan hilang begitu
 * app dibuka ulang (diff folder tidak bisa direkonstruksi belakangan). Isinya
 * hanya path relatif — bukan data berkas — jadi ukurannya kecil.
 */
class TurnFileStore(
    private val dir: File,
    private val maxMessages: Int = 200,
) {

    private val file = File(dir, STORE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Simpan hasil giliran [messageId]; menimpa entri lama untuk id yang sama. */
    fun save(messageId: String, paths: List<String>) {
        synchronized(LOCK) {
            val doc = read()
            val kept = doc.messages.filterNot { it.id == messageId } + TurnFileRecord(messageId, paths)
            write(TurnFileDoc(kept.takeLast(maxMessages)))
        }
    }

    /** Path mentah yang tersimpan untuk [messageId] (tanpa cek keberadaan berkas). */
    fun paths(messageId: String): List<String> = synchronized(LOCK) {
        read().messages.firstOrNull { it.id == messageId }?.paths.orEmpty()
    }

    /**
     * Berkas [messageId] yang **masih ada** di [root] (folder sesi). Berkas yang
     * sudah dihapus user lewat tab Workspace otomatis hilang dari kartu.
     */
    fun files(messageId: String, root: File): List<ChatFile> = synchronized(LOCK) {
        read().messages.firstOrNull { it.id == messageId }?.paths.orEmpty()
            .mapNotNull { rel ->
                val f = File(root, rel)
                if (!f.isFile) null
                else ChatFile(rel, f.name, f.length(), ChatFile.mimeOf(f.name))
            }
    }

    /**
     * Dokumen di-cache berdasarkan mtime berkas: `files()` dipanggil untuk tiap
     * pesan asisten di setiap putaran polling, dan membaca ulang JSON seluruhnya
     * di setiap panggilan itu menghabiskan I/O tanpa alasan.
     */
    private fun read(): TurnFileDoc = synchronized(LOCK) {
        val stamp = if (file.isFile) file.lastModified() else -1L
        val cached = cache
        if (cached != null && cached.path == file.absolutePath && cached.stamp == stamp && stamp > 0L) {
            return@synchronized cached.doc
        }
        val doc = runCatching {
            if (!file.isFile) TurnFileDoc()
            else json.decodeFromString<TurnFileDoc>(file.readText())
        }.getOrDefault(TurnFileDoc())
        cache = Cached(file.absolutePath, stamp, doc)
        doc
    }

    private fun write(doc: TurnFileDoc) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$STORE_NAME.tmp")
            tmp.writeText(json.encodeToString(TurnFileDoc.serializer(), doc))
            // rename menimpa berkas lama dalam satu langkah → tidak ada keadaan
            // setengah tertulis kalau proses mati di tengah.
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            cache = Cached(file.absolutePath, file.lastModified(), doc)
        }
    }

    companion object {
        private const val STORE_NAME = "chat_turn_files.json"

        /** Satu kunci untuk semua instance: dua sesi bisa menyimpan bersamaan. */
        private val LOCK = Any()

        private class Cached(val path: String, val stamp: Long, val doc: TurnFileDoc)

        @Volatile
        private var cache: Cached? = null

        fun of(context: Context): TurnFileStore = TurnFileStore(context.filesDir)
    }
}
