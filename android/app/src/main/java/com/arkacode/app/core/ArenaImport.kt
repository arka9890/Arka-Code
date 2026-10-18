package com.arkacode.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Simpan unduhan Arena AI langsung ke proot workspace.
 *
 * Jalur lama (DownloadManager → /Download → tombol impor manual) tidak bisa
 * jalan otomatis: Android 8–10 menolak membaca /Download tanpa
 * READ_EXTERNAL_STORAGE (yang tidak kami pegang), dan tombol impor menyalin ke
 * folder "default" yang bukan workspace sesi mana pun. Dengan mengunduh sendiri
 * lewat OkHttp, berkas langsung mendarat di <filesDir>/workspace dan langsung
 * terlihat oleh server opencode tanpa langkah tambahan.
 */
object ArenaImport {

    private val http = OkHttpClient()

    suspend fun saveToWorkspace(context: Context, url: String, name: String): File =
        withContext(Dispatchers.IO) {
            val dir = VirtualFs.prootWorkspaceDir(context)
            dir.mkdirs()
            val out = File(dir, safeName(name))
            val response = http.newCall(Request.Builder().url(url).build()).execute()
            response.use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body.byteStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            out
        }

    /** Ambil nama berkas dari Content-Disposition; kalau tak ada, pakai nama aman. */
    fun fileNameFrom(contentDisposition: String?): String? =
        contentDisposition
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }

    // Nama berkas dipakai langsung sebagai path, jadi buang pemisah direktori.
    private fun safeName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "arena-file" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
}
