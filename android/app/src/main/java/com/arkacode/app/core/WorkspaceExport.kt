package com.arkacode.app.core

import android.net.Uri
import com.arkacode.app.net.OpenCodeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **Kebalikan dari WorkspaceImport**: tulis ZIP seluruh isi folder workspace
 * (path relatif ke working dir server) ke uri pilihan pengguna (Storage
 * Access Framework), sehingga proyek bisa "diunduh" keluar dari HP tanpa
 * perlu izin penyimpanan eksternal.
 */
object WorkspaceExport {

    /**
     * @param port password client lokal (sama dengan yang dipakai FilesPane).
     * @param cwd folder sumber, "" = root workspace.
     * @param zipName nama berkas zip tujuan; berkas dgn nama sama di folder
     *        di-skip agar tidak memakannya sendiri.
     * @param out uri hasil ActivityResultContracts.CreateDocument.
     * @return jumlah berkas yang dimasukkan ke ZIP.
     */
    suspend fun exportFolder(
        port: Int,
        password: String,
        cwd: String,
        zipName: String,
        out: Uri,
    ): Int = withContext(Dispatchers.IO) {
        val client = OpenCodeClient(port = port, password = password)
        val leaf = zipName.substringAfterLast('/')
        val stack = ArrayDeque(listOf(cwd))
        var count = 0
        out.openOutputStream()?.use { raw ->
            ZipOutputStream(raw.buffered(64 * 1024)).use { zip ->
                while (stack.isNotEmpty()) {
                    val dir = stack.removeFirst()
                    for (e in client.listFiles(dir)) {
                        if (e.isDir) stack.addLast(e.path)
                        else if (e.path != leaf) {
                            val bytes = client.readFileBytes(e.path, maxBytes = 64_000_000)
                            val entry = ZipEntry(e.path)
                            entry.time = 0L
                            zip.putNextEntry(entry)
                            zip.write(bytes)
                            zip.closeEntry()
                            count++
                        }
                    }
                }
            }
        } ?: throw java.io.IOException("tidak bisa menulis zip")
        count
    }
}
