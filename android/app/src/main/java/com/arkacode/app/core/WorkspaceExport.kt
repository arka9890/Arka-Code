package com.arkacode.app.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.arkacode.app.net.OpenCodeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
        context: Context,
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
        context.contentResolver.openOutputStream(out)?.use { raw ->
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

    /**
     * Salin satu berkas (APK) ke folder hasil OpenDocumentTree — dipakai tab
     * Build untuk "salin otomatis ke folder tujuan" (asli tetap untuk gradle).
     * Kegagalan (grant dicabut, folder hilang, dsb.) kembali sebagai
     * [Result.failure] agar pemanggil bisa menampilkan pesan di log.
     */
    suspend fun copyToTree(
        context: Context,
        treeUri: Uri,
        src: File,
        newName: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val doc = DocumentsContract.createDocument(
                resolver,
                treeUri,
                "application/vnd.android.package-archive",
                newName,
            ) ?: throw IOException("tidak bisa membuat dokumen di folder tujuan")
            try {
                resolver.openOutputStream(doc, "w")?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("tidak bisa menulis $newName")
            } catch (e: Exception) {
                // Copy gagal di tengah — buang parsial supaya tak meninggalkan APK rusak.
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                throw e
            }
            newName
        }
    }
}
