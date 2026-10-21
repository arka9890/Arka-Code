package com.arkacode.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.fileLeafName
import com.arkacode.app.core.WorkspaceExport
import com.arkacode.app.core.WorkspaceImport
import com.arkacode.app.net.FsEntry
import com.arkacode.app.net.OpenCodeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Panel Files untuk dipakai di dalam tab chat (F2+): jelajahi workspace
 * server (tree per folder), baca file teks, preview markdown/gambar,
 * cari file. Path relatif ke working dir server (/root/workspace).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesPane(port: Int, password: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val client = remember(port, password) { OpenCodeClient(port = port, password = password) }
    val filesContext = LocalContext.current

    var cwd by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FsEntry>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var openedPath by remember { mutableStateOf<String?>(null) }
    var openedText by remember { mutableStateOf<String?>(null) }
    var openedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun loadDir(dir: String) {
        job?.cancel()
        loading = true
        error = null
        openedPath = null
        openedBytes = null
        job = scope.launch {
            entries = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.listFiles(dir)
                }
            }.onFailure { error = it.message }.getOrNull()
            loading = false
        }
    }

    fun openFile(path: String) {
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            if (isImageExt(path)) {
                val bytes = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        client.readFileBytes(path)
                    }
                }.onFailure { error = it.message }.getOrNull()
                if (bytes != null) {
                    openedPath = path
                    openedBytes = bytes
                    openedText = ""
                }
            } else {
                val text = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        client.readFile(path)
                    }
                }.onFailure { error = it.message }.getOrNull()
                if (text != null) {
                    openedPath = path
                    openedBytes = null
                    openedText = text
                }
            }
            loading = false
        }
    }

    fun search() {
        job?.cancel()
        loading = true
        searching = true
        error = null
        openedPath = null
        openedBytes = null
        job = scope.launch {
            entries = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.findFiles(query)
                }
            }.onFailure { error = it.message }.getOrNull()
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadDir("") }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = WorkspaceImport.displayName(filesContext, uri) ?: "berkas"
        Toast.makeText(filesContext, UploadText.progress(name), Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = runCatching {
                WorkspaceImport.importUri(filesContext, uri, cwd)
            }
            result.onSuccess {
                Toast.makeText(
                    filesContext,
                    UploadText.done(it.size, name),
                    Toast.LENGTH_LONG
                ).show()
                loadDir(cwd)
            }.onFailure {
                Toast.makeText(
                    filesContext,
                    UploadText.failed(name, it.message ?: "?"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val pickExportZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                WorkspaceExport.exportFolder(port, password, cwd, ExportText.zipName(cwd), uri)
            }.onSuccess { count ->
                Toast.makeText(filesContext, ExportText.done(count), Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(
                    filesContext,
                    ExportText.failed(it.message ?: "?"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (openedPath != null) {
                    TextButton(onClick = { openedPath = null }) { Text(I18n.s.backToList) }
                }
                Text(
                    if (openedPath != null) fileLeafName(openedPath!!) else I18n.s.filesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    searching = false
                    query = ""
                    loadDir(cwd)
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = I18n.s.reloadFiles)
                }
                if (openedPath == null) {
                    IconButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                        Icon(
                            Icons.Default.Upload,
                            contentDescription = UploadText.pick,
                        )
                    }
                    IconButton(onClick = { pickExportZip.launch(ExportText.zipName(cwd)) }) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = ExportText.pick,
                        )
                    }
                }
            }
            if (openedPath == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(I18n.s.searchFile) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { search() }, enabled = query.isNotBlank()) {
                        Icon(Icons.Default.Search, contentDescription = I18n.s.search)
                    }
                }
                if (cwd.isNotBlank() && !searching) {
                    Breadcrumb(
                        cwd = cwd,
                        onRoot = {
                            cwd = ""
                            loadDir("")
                        },
                        onJump = {
                            cwd = it
                            loadDir(it)
                        },
                    )
                }
                if (searching) {
                    TextButton(onClick = {
                        searching = false
                        query = ""
                        loadDir(cwd)
                    }) { Text(I18n.s.closeSearch) }
                }
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { error = null }) { Text(I18n.s.close) }
            }
        }
        if (loading && entries == null && openedPath == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(8.dp))
                Text(I18n.s.loading, style = MaterialTheme.typography.bodySmall)
            }
        }
        val open = openedPath
        if (open != null) {
            FileView(path = open, content = openedText ?: I18n.s.loading, imageBytes = openedBytes)
        } else {
            val list = entries ?: emptyList()
            if (list.isEmpty() && !loading) {
                Text(I18n.s.empty, style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val dirs = list.filter { it.isDir }.sortedBy { it.path }
                    val files = list.filterNot { it.isDir }.sortedBy { it.path }
                    items(dirs + files, key = { it.path }) { e ->
                        ClayCard(contentPadding = PaddingValues(0.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (e.isDir) {
                                            cwd = e.path
                                            loadDir(e.path)
                                        } else {
                                            openFile(e.path)
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (e.isDir) "📁" else "📄")
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    fileLeafName(e.path),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun isImageExt(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")

@Composable
private fun Breadcrumb(cwd: String, onRoot: () -> Unit, onJump: (String) -> Unit) {
    val parts = cwd.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onRoot) { Text("/") }
        var acc = ""
        parts.forEach { part ->
            acc = if (acc.isEmpty()) part else "$acc/$part"
            val target = acc
            Text("/")
            TextButton(onClick = { onJump(target) }) { Text(part, maxLines = 1) }
        }
    }
}

@Composable
private fun FileView(path: String, content: String, imageBytes: ByteArray?) {
    val context = LocalContext.current
    var copied by remember(path) { mutableStateOf(false) }
    val ext = path.substringAfterLast('.', "").lowercase()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                path,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("file", content))
                copied = true
            }) {
                Text(if (copied) I18n.s.copied else I18n.s.copy, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (ext == "md" || ext == "markdown") {
            MarkdownText(markdown = content)
        } else if (imageBytes != null) {
            val bitmap = remember(imageBytes) { decodePreview(imageBytes) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = path,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(I18n.s.previewFail, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun decodePreview(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}.getOrNull()
