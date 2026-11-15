package com.arkacode.app.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.arkacode.app.net.OpenCodeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
        context.contentResolver.openOutputStream(out)?.use { raw ->
            ZipOutputStream(raw.buffered(64 * 1024)).use { zip ->
                zipFolder(client, cwd, leaf, zip)
            }
        } ?: throw IOException("tidak bisa menulis zip")
    }

    /**
     * Bundel folder [cwd] ("" = root workspace) jadi ZIP lalu simpan LANGSUNG
     * ke folder Download publik — tanpa dialog pilih lokasi (SAF).
     * Android 10+ lewat MediaStore.Downloads (tanpa izin penyimpanan),
     * Android 9 ke bawah lewat path publik (WRITE_EXTERNAL_STORAGE maxSdk 28).
     * @return jumlah berkas yang dimasukkan ke ZIP.
     */
    suspend fun saveFolderZipToDownloads(
        context: Context,
        port: Int,
        password: String,
        cwd: String,
        zipName: String,
    ): Int = withContext(Dispatchers.IO) {
        val client = OpenCodeClient(port = port, password = password)
        val leaf = zipName.substringAfterLast('/')
        var count = 0
        writeToDownloads(context, leaf, "application/zip") { raw ->
            ZipOutputStream(raw.buffered(64 * 1024)).use { zip ->
                runBlocking {
                    count = zipFolder(client, cwd, leaf, zip)
                }
            }
        }
        count
    }

    /** BFS seluruh isi folder workspace → entri ZIP. Mengembalikan jumlah berkas. */
    private suspend fun zipFolder(
        client: OpenCodeClient,
        cwd: String,
        leaf: String,
        zip: ZipOutputStream,
    ): Int {
        val stack = ArrayDeque(listOf(cwd))
        var count = 0
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
        return count
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
            // Provider di sebagian perangkat menolak tree URI mentah saat
            // createDocument ("Invalid URI: content://…/tree/…") — konversi dulu ke
            // document URI (…/tree/<id>/document/<id>) sesuai resep platform.
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val doc = DocumentsContract.createDocument(
                resolver,
                parentDoc,
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

    /**
     * Ekspor satu berkas ke lokasi yang dipilih pengguna via OpenDocument.
     * @param port password client lokal
     * @param path path berkas di workspace server
     * @param out uri hasil ActivityResultContracts.CreateDocument
     */
    suspend fun exportFile(
        context: Context,
        port: Int,
        password: String,
        path: String,
        out: Uri,
    ): Long = withContext(Dispatchers.IO) {
        val client = OpenCodeClient(port = port, password = password)
        val bytes = client.readFileBytes(path, maxBytes = 64_000_000)
        context.contentResolver.openOutputStream(out)?.use { outStream ->
            outStream.write(bytes)
        } ?: throw IOException("tidak bisa menulis berkas")
        bytes.size.toLong()
    }

    /**
     * Unduh satu berkas workspace LANGSUNG ke folder Download publik, tanpa
     * dialog pilih lokasi. Nama berkas bentrok di-rename otomatis oleh
     * MediaStore ("file (1).txt"), jadi berkas lama tidak tertimpa.
     * @return jumlah byte yang ditulis.
     */
    suspend fun saveFileToDownloads(
        context: Context,
        port: Int,
        password: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val client = OpenCodeClient(port = port, password = password)
        val bytes = client.readFileBytes(path, maxBytes = 64_000_000)
        val name = path.substringAfterLast('/').ifBlank { "berkas" }
        writeToDownloads(context, name, mimeOf(name)) { it.write(bytes) }
        bytes.size.toLong()
    }

    /** MIME kasar berdasarkan ekstensi — cukup untuk MediaStore memilih folder. */
    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "txt", "md", "log", "kt", "java", "sh" -> "text/plain"
        else -> "application/octet-stream"
    }

    /**
     * Corong tulis ke folder Download publik.
     *
     * Android 10+ (API 29): insert ke MediaStore.Downloads lalu tulis lewat
     * stream — tidak butuh izin penyimpanan sama sekali. Android 9 ke bawah:
     * belum ada koleksi Downloads, jadi tulis langsung ke path publik
     * (izin WRITE_EXTERNAL_STORAGE di-declare maxSdkVersion 28).
     */
    private fun writeToDownloads(
        context: Context,
        name: String,
        mime: String,
        write: (OutputStream) -> Unit,
    ) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: throw IOException("tidak bisa membuat berkas di Download")
            try {
                resolver.openOutputStream(uri)?.use(write)
                    ?: throw IOException("tidak bisa menulis $name")
            } catch (e: Exception) {
                // Tulis gagal di tengah — buang entri parsial supaya tidak
                // menyisakan berkas 0 byte / rusak di folder Download.
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("folder Download tidak ada")
            File(dir, name).outputStream().use(write)
        }
    }
}
