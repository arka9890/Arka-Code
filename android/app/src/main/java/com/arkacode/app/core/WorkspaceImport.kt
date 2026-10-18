package com.arkacode.app.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Salin berkas yang dipilih pengguna (lewat Storage Access Framework) ke proot
 * workspace. Bila berkasnya ZIP, langsung diekstrak.
 *
 * Dipakai menggantikan alur "unduh di Arena → impor dari /Download": Arena
 * dibuka lewat Chrome Custom Tab, sehingga WebView kita tak pernah menerima
 * callback unduhan sama sekali. Memilih berkas sendiri tidak butuh izin
 * penyimpanan eksternal dan bekerja di semua versi Android.
 */
object WorkspaceImport {

    /**
     * @param destRelPath path relatif proot workspace ("", "foo/bar", …).
     * @return jumlah berkas yang berakhir tersimpan (isi ZIP dihitung per berkas).
     */
    suspend fun importUri(
        context: Context,
        uri: Uri,
        destRelPath: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val root = VirtualFs(context).prootWorkspaceDir()
        // resolveProot menolak path kosong atau yang membawa ".."; untuk yang
        // kosong kita pakai root langsung, untuk yang ".." tetap tolak.
        val rel = destRelPath.trim()
        if (rel.split('/').any { it == ".." }) throw IOException("jalur tujuan tidak aman")
        val dest = if (rel.isEmpty()) root
        else VirtualFs(context).resolveProot(rel) ?: throw IOException("jalur tujuan tidak aman")
        dest.mkdirs()

        val name = displayName(context, uri) ?: "upload"
        val tmp = File.createTempFile("arka-upload", ".tmp", context.cacheDir)
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("tidak bisa membaca berkas terpilih")
            if (bytes.isEmpty()) throw IOException("berkas kosong")
            tmp.writeBytes(bytes)

            if (isZip(name, bytes)) {
                extractZip(tmp, dest)
            } else {
                val out = File(dest, safeName(name))
                tmp.copyTo(out, overwrite = true)
                listOf(out.name)
            }
        } finally {
            tmp.delete()
        }
    }

    /** ZIP dikenali dari ekstensi atau dari signature PK\\x03\\x04. */
    fun isZip(name: String, bytes: ByteArray): Boolean =
        name.endsWith(".zip", ignoreCase = true) ||
            (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte())

    private fun extractZip(zipFile: File, dest: File): List<String> {
        dest.mkdirs()
        val written = mutableListOf<String>()
        val rootPath = dest.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                // Zip Slip: tolak nama entry yang membawa "../" keluar dari dest.
                val target = File(dest, entry.name)
                if (!target.canonicalPath.startsWith(rootPath)) {
                    throw IOException("nama berkas tak aman di ZIP: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zin.copyTo(it) }
                    written += entry.name
                }
                zin.closeEntry()
            }
        }
        if (written.isEmpty()) throw IOException("ZIP berisi tidak ada berkas")
        return written
    }

    fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0) null else c.getString(idx)
            }
        }.getOrNull() ?: uri.lastPathSegment

    // Nama dipakai langsung sebagai path, jadi buang pemisah direktori.
    fun safeName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "upload-file" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
}
