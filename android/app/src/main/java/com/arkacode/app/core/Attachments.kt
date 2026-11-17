package com.arkacode.app.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Berkas lampiran siap kirim ke server.
 *
 * [uri] memakai skema `data:<mime>;base64,` — bukan `file:`. OpenCode v2
 * meneruskan URL `file:` apa adanya ke protokol OpenAI Chat sehingga gambar
 * ditolak dengan "OpenAI Chat media must contain valid base64", sedangkan
 * `data:` lolos pemeriksaan media dan sampai ke model.
 */
data class Attachment(val name: String, val uri: String, val mime: String)

/** Hasil impor berkas lampiran; gagal disertai alasan agar UI bisa memakai teks i18n. */
sealed interface ImportResult {
    data class Success(val attachment: Attachment) : ImportResult
    data class Failure(val reason: Reason) : ImportResult

    enum class Reason { TOO_LARGE, UNREADABLE }
}

/**
 * Menyiapkan berkas terpilih untuk dikirim: membaca isinya, membangun `data:` URI,
 * dan menyalinnya ke workspace sesi (berkas jadi ada di cwd proot sehingga agent
 * tetap bisa membacanya lewat tool). Salinan bersifat best-effort — pengiriman
 * tidak bergantung padanya.
 */
object Attachments {

    /** Batas per lampiran, di bawah batas ter-decode 20 MiB milik server. */
    const val MAX_BYTES = 6L * 1024 * 1024

    /**
     * Impor gambar dari bytes (untuk clipboard paste).
     * [name] berisi nama berkas dengan ekstensi yang sesuai.
     * [mime] adalah tipe MIME (contoh: "image/png", "image/jpeg").
     */
    fun importFromBytes(context: Context, sessionId: String, name: String, bytes: ByteArray, mime: String): ImportResult {
        if (bytes.size > MAX_BYTES) {
            return ImportResult.Failure(ImportResult.Reason.TOO_LARGE)
        }

        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        copyBestEffort(context, sessionId, name, bytes)
        return ImportResult.Success(Attachment(name, "data:$mime;base64,$encoded", mime))
    }

    fun import(context: Context, sessionId: String, uri: Uri): ImportResult {
        val name = safeName(displayName(context, uri) ?: "file")
        val input = context.contentResolver.openInputStream(uri)
            ?: return ImportResult.Failure(ImportResult.Reason.UNREADABLE)
        val bytes = try {
            input.use { readLimited(it, MAX_BYTES) }
        } catch (e: Exception) {
            return ImportResult.Failure(ImportResult.Reason.UNREADABLE)
        } ?: return ImportResult.Failure(ImportResult.Reason.TOO_LARGE)

        val mime = mimeType(context, uri, name)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        copyBestEffort(context, sessionId, name, bytes)
        return ImportResult.Success(Attachment(name, "data:$mime;base64,$encoded", mime))
    }

    /** `null` bila berkas melebihi [limit] (dibatasi di tengah baca agar tak kehabisan memori). */
    private fun readLimited(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Salin berkas ke `<workspace>/sessions/<sid>/uploads/` agar ikut terbawa ke model. */
    private fun copyBestEffort(context: Context, sessionId: String, name: String, bytes: ByteArray) {
        runCatching {
            val fs = VirtualFs(context)
            val uploads = File(fs.workspaceDir(sessionId), "uploads").apply { mkdirs() }
            uniqueFile(uploads, name).writeBytes(bytes)
        }
    }

    /** MIME dari ContentResolver; fallback ke ekstensi berkas, lalu octet-stream. */
    private fun mimeType(context: Context, uri: Uri, name: String): String =
        context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun displayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i) else null
                    } else null
                }
        }.getOrNull()?.let { if (it.isNotBlank()) return it }
        return uri.lastPathSegment
    }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "file" }
        return cleaned
    }

    private fun uniqueFile(dir: File, name: String): File {
        val f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        var candidate = File(dir, "$base-$n$ext")
        while (candidate.exists()) {
            n++
            candidate = File(dir, "$base-$n$ext")
        }
        return candidate
    }
}
