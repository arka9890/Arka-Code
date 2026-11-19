package com.arkacode.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decode berkas gambar dengan sisi terpanjang maksimal [maxPx] px
 * (`inSampleSize` dinaikkan sampai muat). `null` bila bukan gambar / rusak.
 *
 * Membaca langsung dari [file] — bukan bytes di memori — supaya pemanggil tidak
 * perlu menahan isi berkas di heap.
 */
internal fun decodePreviewFile(file: File, maxPx: Int = 2048): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
}.getOrNull()

/**
 * Decode dari bytes (dipakai pratinjau di tab Workspace, yang isinya memang
 * sudah dibaca dari server).
 */
internal fun decodePreview(bytes: ByteArray, maxPx: Int = 2048): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}.getOrNull()

/**
 * Dialog layar penuh untuk pratinjau gambar di chat.
 *
 * Decode dijalankan di IO saat dialog dibuka — bukan sejak kartu dirender —
 * lalu ketuk di mana saja untuk menutup.
 */
@Composable
fun ImagePreviewDialog(
    file: File,
    onDismiss: () -> Unit,
) {
    var bitmap by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(file.path) { mutableStateOf(false) }

    LaunchedEffect(file.path) {
        val decoded = withContext(Dispatchers.IO) { decodePreviewFile(file) }
        bitmap = decoded
        failed = decoded == null
    }

    // Gagal decode: jangan tinggalkan dialog kosong. Ditutup dari efek, bukan
    // saat composition, supaya tidak mengubah state di tengah render.
    if (failed) {
        LaunchedEffect(file.path) { onDismiss() }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            val shown = bitmap
            if (shown == null) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color.White)
            } else {
                Image(
                    bitmap = shown,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
            }
        }
    }
}