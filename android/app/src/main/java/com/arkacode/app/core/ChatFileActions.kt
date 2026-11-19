package com.arkacode.app.core

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.arkacode.app.net.ChatFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Aksi untuk berkas yang ditampilkan di chat: unduh ke folder Download,
 * bagikan ke aplikasi lain, atau buka dengan pemutar sistem.
 *
 * Semua path yang masuk adalah path relatif terhadap folder sesi dan
 * diresolusi ulang di sini — berkas dari server tidak dipercaya untuk
 * menentukan lokasi di disk.
 */
object ChatFileActions {

    /**
     * Resolusi path relatif terhadap [root]: path absolut dan `..` ditolak.
     *
     * @return berkas target, atau `null` bila path tidak sah / keluar dari [root].
     */
    internal fun resolve(root: File, path: String): File? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) return null
        val normalized = trimmed.replace('\\', '/')
        if (normalized.split('/').any { it == ".." }) return null
        val target = File(root, normalized)
        val rootPath = root.canonicalPath + File.separator
        return if (target.canonicalPath.startsWith(rootPath)) target else null
    }

    /** Salin berkas ke folder Download publik. Gagal → [Result.failure] berisi sebabnya. */
    suspend fun save(context: Context, root: File, file: ChatFile): Result<Long> =
        withContext(Dispatchers.IO) {
            val target = resolve(root, file.path)
            if (target == null || !target.isFile) {
                return@withContext Result.failure(IOException("berkas tidak ada"))
            }
            runCatching { WorkspaceExport.saveFileToDownloads(context, target, file.mime) }
        }

    /** Bagikan berkas lewat chooser (`ACTION_SEND` + URI FileProvider). */
    fun share(context: Context, root: File, file: ChatFile) {
        val target = resolve(root, file.path)?.takeIf { it.isFile } ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(context, authority(context), target)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = file.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Sebagian penerima hanya membaca URI dari ClipData.
                clipData = ClipData.newRawUri(file.name, uri)
            }
            startChooser(context, Intent.createChooser(send, file.name))
        }
    }

    /** Buka berkas dengan aplikasi pemutar bawaan (dipakai untuk video sangat besar). */
    fun open(context: Context, root: File, file: ChatFile) {
        val target = resolve(root, file.path)?.takeIf { it.isFile } ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(context, authority(context), target)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, file.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(file.name, uri)
            }
            context.startActivity(view)
        }
    }

    private fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** Chooser dari luar Activity butuh flag NEW_TASK. */
    private fun startChooser(context: Context, chooser: Intent) {
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
