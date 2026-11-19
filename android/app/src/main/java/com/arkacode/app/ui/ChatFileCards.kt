package com.arkacode.app.ui

import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.arkacode.app.core.ChatFileActions
import com.arkacode.app.net.ChatFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Berapa kartu yang ditampilkan sebelum diringkas jadi "+N berkas lagi". */
private const val MAX_SHOWN = 12

/** Di atas batas ini gambar tidak didecode (hindari OOM) dan video tidak diputar inline. */
private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
private const val MAX_VIDEO_BYTES = 512L * 1024 * 1024

/**
 * Kartu berkas hasil model di dalam bubble jawaban.
 *
 * Gambar tampil sebagai pratinjau, video bisa diputar di tempat, berkas lain
 * jadi baris nama + ukuran. Semua jenis punya tombol Unduh dan Bagikan —
 * berkas yang terlihat di chat harus selalu bisa diselamatkan ke HP.
 */
@Composable
fun ChatFileCards(files: List<ChatFile>, sessionRoot: File) {
    if (files.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        files.take(MAX_SHOWN).forEach { file -> ChatFileCard(file, sessionRoot) }
        if (files.size > MAX_SHOWN) {
            Text(
                I18n.s.filesMore(files.size - MAX_SHOWN),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun ChatFileCard(file: ChatFile, sessionRoot: File) {
    val target = remember(file.path) { ChatFileActions.resolve(sessionRoot, file.path) }
    if (target == null || !target.isFile) return
    val size = if (file.size > 0L) file.size else target.length()
    ClayCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth()) {
            when {
                file.kind == ChatFile.Kind.IMAGE && size <= MAX_IMAGE_BYTES -> ImageBlock(target)
                file.kind == ChatFile.Kind.VIDEO && size <= MAX_VIDEO_BYTES -> VideoBlock(target)
            }
            FileRow(file, sessionRoot, size)
        }
    }
}

/** Pratinjau gambar; ketuk untuk membuka layar penuh. */
@Composable
private fun ImageBlock(target: File) {
    var bytes by remember(target.path) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(target.path) { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(target.path) {
        val loaded = withContext(Dispatchers.IO) { runCatching { target.readBytes() }.getOrNull() }
        bytes = loaded
        failed = loaded == null
    }
    val bitmap = remember(bytes) { bytes?.let { decodePreview(it) } }
    when {
        bitmap != null -> {
            Image(
                bitmap = bitmap,
                contentDescription = target.name,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .clickable { full = true },
            )
            val data = bytes
            if (full && data != null) ImagePreviewDialog(data, onDismiss = { full = false })
        }
        failed -> Text(
            I18n.s.previewFail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** Pemutar video inline dengan pengendali bawaan Android. */
@Composable
private fun VideoBlock(target: File) {
    val holder = remember(target.path) { arrayOfNulls<VideoView>(1) }
    var playing by remember(target.path) { mutableStateOf(false) }
    DisposableEffect(target.path) {
        onDispose { runCatching { holder[0]?.stopPlayback() } }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    runCatching {
                        setVideoURI(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", target))
                    }
                    holder[0] = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!playing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable {
                        runCatching { holder[0]?.start() }
                        playing = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = I18n.s.fileOpen,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp),
                )
            }
        }
    }
}

/** Nama berkas + ukuran + tombol aksi. Dipakai semua jenis berkas. */
@Composable
private fun FileRow(
    file: ChatFile,
    sessionRoot: File,
    size: Long,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = I18n.s
    var saving by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (file.kind == ChatFile.Kind.VIDEO) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                file.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatSize(size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (size > MAX_VIDEO_BYTES && file.kind == ChatFile.Kind.VIDEO) {
            TextButton(onClick = { ChatFileActions.open(context, sessionRoot, file) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        TextButton(
            enabled = !saving,
            onClick = {
                saving = true
                scope.launch {
                    val res = ChatFileActions.save(context, sessionRoot, file)
                    saving = false
                    Toast.makeText(
                        context,
                        if (res.isSuccess) strings.fileSaved else strings.fileSaveFailed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                if (saving) strings.working else strings.fileSave,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        TextButton(onClick = { ChatFileActions.share(context, sessionRoot, file) }) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                strings.fileShare,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Ukuran berkas yang mudah dibaca (B/KB/MB/GB). */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
