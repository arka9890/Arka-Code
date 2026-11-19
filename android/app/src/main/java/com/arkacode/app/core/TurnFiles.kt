package com.arkacode.app.core

import android.content.Context
import com.arkacode.app.net.ChatFile
import java.io.File

/** Sidik satu berkas: ukuran + waktu ubah, cukup untuk mendeteksi berkas baru/berubah. */
data class TurnStamp(val size: Long, val mtime: Long)

/**
 * Melacak berkas yang lahir selama satu giliran chat.
 *
 * Model menghasilkan berkas dengan menjalankan perintah di workspace (python,
 * ffmpeg, `write`), jadi cara paling umum untuk mengetahuinya adalah
 * membandingkan daftar isi folder sesi sebelum dan sesudah giliran — bukan
 * membaca isi pesan, yang hanya memuat nama tool.
 *
 * Semua fungsi di sini **bloking** (bukan `suspend`): pemanggil yang
 * menaruhnya di `Dispatchers.IO`. Ini disengaja supaya diff tetap bisa
 * dihitung dari blok `finally` saat turn dibatalkan (tombol Stop).
 */
object TurnFiles {

    /**
     * Folder yang tidak ikut dipindai: lampiran user dan direktori dependensi
     * yang isinya ribuan berkas — memindainya lambat dan hasilnya hanya noise.
     */
    private val SKIP_DIRS = setOf(
        "uploads",
        "node_modules",
        ".git",
        ".venv",
        "__pycache__",
        "build",
        ".gradle",
        "target",
        "dist",
        "vendor",
        ".cache",
        ".next",
    )

    /**
     * Pindai isi [root] secara rekursif.
     *
     * @return peta path relatif (pemisah `/`) → [TurnStamp], atau `null` bila
     *         jumlah berkas melebihi [maxEntries]. `null` berarti "terlalu
     *         besar untuk dilacak" — pemanggil sebaiknya tidak menampilkan
     *         kartu berkas daripada menampilkan sebagian.
     */
    internal fun scan(root: File, maxEntries: Int = 10_000): Map<String, TurnStamp>? {
        if (!root.isDirectory) return emptyMap()
        val rootPath = root.absolutePath
        val out = LinkedHashMap<String, TurnStamp>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    if (f.name !in SKIP_DIRS) stack.addLast(f)
                } else if (f.isFile) {
                    if (out.size >= maxEntries) return null
                    val rel = f.absolutePath.removePrefix(rootPath).trimStart('/')
                    if (rel.isNotEmpty()) out[rel] = TurnStamp(f.length(), f.lastModified())
                }
            }
        }
        return out
    }

    /**
     * Berkas yang baru atau berubah dibanding [before], terbaru lebih dulu,
     * maksimum [limit]. Berkas yang dihapus tidak ikut (tidak ada yang bisa
     * ditampilkan), dan berkas yang hanya dibaca tidak ikut (tidak berubah).
     */
    internal fun diff(
        before: Map<String, TurnStamp>,
        root: File,
        limit: Int = 50,
    ): List<ChatFile> {
        val now = scan(root) ?: return emptyList()
        return now.entries
            .filter { (path, stamp) ->
                val old = before[path]
                old == null || old.size != stamp.size || old.mtime != stamp.mtime
            }
            .sortedByDescending { it.value.mtime }
            .take(limit)
            .map { (path, stamp) ->
                val name = path.substringAfterLast('/')
                ChatFile(path, name, stamp.size, ChatFile.mimeOf(name))
            }
    }

    /** Snapshot folder sesi sebelum giliran dikirim. `null` = terlalu besar/ tak terbaca. */
    fun snapshot(context: Context, sessionId: String): Map<String, TurnStamp>? =
        scan(workspace(context, sessionId))

    /** Berkas hasil giliran. [before] `null` → tidak ada yang bisa dibandingkan. */
    fun changed(
        context: Context,
        sessionId: String,
        before: Map<String, TurnStamp>?,
    ): List<ChatFile> = if (before == null) emptyList() else diff(before, workspace(context, sessionId))

    private fun workspace(context: Context, sessionId: String): File =
        VirtualFs(context).workspaceDir(sessionId)
}
