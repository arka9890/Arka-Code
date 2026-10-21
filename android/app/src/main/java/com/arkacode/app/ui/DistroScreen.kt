package com.arkacode.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.DistroCatalog
import com.arkacode.app.core.DistroManager
import com.arkacode.app.core.DistroSpec
import com.arkacode.app.core.EngineManager
import com.arkacode.app.core.RuntimeMode
import com.arkacode.app.core.SessionUiPrefs
import com.arkacode.app.core.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar "Distro": pilih runtime eksekusi command + distro rootfs.
 *
 * Dua hal bisa dipilih:
 *  1. **Runtime** — proot (rootfs di dalam app) atau Termux (app Termux yang
 *     terpasang di perangkat).
 *  2. **Distro** — Alpine (versi 3.20 / 3.21 / 3.22 / edge) atau rootfs
 *     sendiri (URL / berkas) untuk yang butuh basis Debian/Ubuntu.
 *
 * Rootfs tiap distro disimpan di folder terpisah, jadi user bisa memasang
 * beberapa distro lalu berganti-ganti tanpa mengunduh ulang.
 */
@Composable
fun DistroScreen(
    engine: EngineManager,
    onBack: () -> Unit,
    onRuntimeChanged: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val uiPrefs = remember { SessionUiPrefs(context) }
    val termux = remember { TermuxBridge(context) }

    var runtime by remember { mutableStateOf(RuntimeMode.PROOT) }
    var distroId by remember { mutableStateOf(DistroCatalog.ALPINE_320.id) }
    var customUrl by remember { mutableStateOf("") }
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sizes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var prootReady by remember { mutableStateOf(true) }
    var termuxStatus by remember { mutableStateOf(TermuxBridge.Status(false, false, "")) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var progressLabel by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    /** ABI perangkat — menentukan rootfs mana yang tersedia. */
    val deviceAbi = remember {
        android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }

    /** Nama kelompok distro yang sudah diterjemahkan. */
    fun groupLabel(group: String): String = when (group) {
        "Lain-lain" -> I18n.s.distroGroupOther
        "Kustom" -> I18n.s.distroGroupCustom
        else -> group
    }

    /** Status terpasang per distro — dicek cepat per folder, tanpa hitung ukuran rootfs. */
    fun refresh() {
        scope.launch {
            val (ids, sz) = withContext(Dispatchers.IO) {
                val ready = mutableSetOf<String>()
                val sizes = mutableMapOf<String, String>()
                DistroCatalog.ALL.forEach { spec ->
                    val dm = DistroManager(context, spec)
                    if (dm.isInstalled()) {
                        ready += spec.id
                        val bytes = dm.rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        sizes[spec.id] = "%.1f MB".format(bytes / 1024.0 / 1024.0)
                    }
                }
                ready to sizes
            }
            installedIds = ids
            sizes = sz
            prootReady = withContext(Dispatchers.IO) {
                val dm = DistroManager(context, DistroCatalog.ALPINE_320)
                dm.prootBinary() != null && dm.loaderBinary() != null
            }
            termuxStatus = withContext(Dispatchers.IO) { termux.status() }
        }
    }

    LaunchedEffect(Unit) {
        runtime = withContext(Dispatchers.IO) { runCatching { uiPrefs.runtimeMode() }.getOrDefault(RuntimeMode.PROOT) }
        distroId = withContext(Dispatchers.IO) { runCatching { uiPrefs.distroId() }.getOrDefault(DistroCatalog.ALPINE_320.id) }
        customUrl = withContext(Dispatchers.IO) { runCatching { uiPrefs.customRootfsUrl() }.getOrDefault("") }
        refresh()
    }

    fun selectRuntime(mode: RuntimeMode) {
        runtime = mode
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { uiPrefs.setRuntimeMode(mode) } }
        }
        message = I18n.s.distroSaved
        onRuntimeChanged()
    }

    fun selectDistro(spec: DistroSpec) {
        distroId = spec.id
        engine.setDistro(spec)
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { uiPrefs.setDistroId(spec.id) }
            }
        }
        message = I18n.s.distroSaved
        onRuntimeChanged()
    }

    fun install(spec: DistroSpec) {
        busy = true
        progress = 0
        message = null
        scope.launch {
            val specToInstall = spec
            engine.setDistro(specToInstall)
            val result = engine.setupDistro(
                customRootfsUrl = if (specToInstall.custom) customUrl.ifBlank { null } else null,
            ) { p, label ->
                scope.launch {
                    progress = p
                    progressLabel = label
                }
            }
            busy = false
            progress = 0
            message = result.exceptionOrNull()?.message
                ?: "${specToInstall.label} siap."
            refresh()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ClayHeader(
                title = I18n.s.navDistro,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                leading = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.s.back)
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                message?.let {
                    ClayCard(modifier = Modifier.fillMaxWidth()) {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                        TextButton(onClick = { message = null }) { Text(I18n.s.close) }
                    }
                }

                // --- Runtime --------------------------------------------------
                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(I18n.s.distroRuntime, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ClayPill(
                                text = I18n.s.distroRuntimeProot,
                                selected = runtime == RuntimeMode.PROOT,
                                onClick = { selectRuntime(RuntimeMode.PROOT) },
                            )
                            ClayPill(
                                text = I18n.s.distroRuntimeTermux,
                                selected = runtime == RuntimeMode.TERMUX,
                                onClick = { selectRuntime(RuntimeMode.TERMUX) },
                            )
                        }
                        if (!prootReady) {
                            Text(
                                I18n.s.distroProotMissing,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (runtime == RuntimeMode.TERMUX) {
                            Text(
                                termuxStatus.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (termuxStatus.ready) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = {
                                    termux.requestPermissionIntent()?.let {
                                        runCatching { context.startActivity(it) }
                                    }
                                }) { Text(I18n.s.distroTermuxAllow) }
                                TextButton(onClick = {
                                    termux.openTermuxIntent()?.let {
                                        runCatching { context.startActivity(it) }
                                    }
                                }) { Text(I18n.s.distroTermuxOpen) }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { refresh() }) { Text(I18n.s.reload) }
                            }
                        }
                    }
                }

                // --- Distro ----------------------------------------------------
                if (runtime == RuntimeMode.PROOT) {
                    DistroCatalog.grouped().forEach { (group, items) ->
                        Text(
                            groupLabel(group),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        items.forEach { spec ->
                            val isActive = spec.id == distroId
                            val isInstalled = spec.id in installedIds
                            val supported = spec.supports(deviceAbi)
                            ClayCard(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            spec.label,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (isActive) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = I18n.s.distroActive,
                                                modifier = Modifier.width(16.dp),
                                            )
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            if (isInstalled) I18n.s.distroInstalled else I18n.s.distroNotInstalled,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        spec.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            "${I18n.s.distroPackageManager}: ${spec.packageManager}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (spec.approxSizeMb > 0) {
                                            Text(
                                                "· ${I18n.s.distroSize}: ~${spec.approxSizeMb} MB",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        sizes[spec.id]?.let {
                                            Text(
                                                "· $it",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (!supported) {
                                        Text(
                                            I18n.s.distroNotAvailable,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }

                                    if (spec.custom) {
                                        OutlinedTextField(
                                            value = customUrl,
                                            onValueChange = { customUrl = it },
                                            label = { Text(I18n.s.distroCustomUrl) },
                                            placeholder = { Text(I18n.s.distroCustomUrlHint) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(
                                            onClick = { selectDistro(spec) },
                                            enabled = !isActive && supported,
                                        ) { Text(I18n.s.distroActive) }
                                        TextButton(
                                            onClick = { install(spec) },
                                            enabled = !busy && prootReady && supported &&
                                                (!spec.custom || customUrl.isNotBlank()),
                                        ) { Text(I18n.s.distroInstall) }
                                        if (isInstalled) {
                                            TextButton(onClick = {
                                                busy = true
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        DistroManager(context, spec).remove()
                                                    }
                                                    busy = false
                                                    refresh()
                                                }
                                            }) { Text(I18n.s.distroRemove) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (busy) {
                        ClayCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.width(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(progressLabel.ifBlank { "…" }, style = MaterialTheme.typography.bodySmall)
                                }
                                LinearProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
