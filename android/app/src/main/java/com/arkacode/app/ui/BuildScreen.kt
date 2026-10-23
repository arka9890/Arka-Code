package com.arkacode.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.AdbFields
import com.arkacode.app.core.BuildPrefs
import com.arkacode.app.core.BuildSupport
import com.arkacode.app.core.DistroManager
import com.arkacode.app.core.EngineManager
import com.arkacode.app.core.ExecRunner
import com.arkacode.app.core.ExecSettings
import com.arkacode.app.core.TermuxBridge
import com.arkacode.app.core.ToolchainSetup
import com.arkacode.app.core.VirtualFs
import com.arkacode.app.core.WorkspaceExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Path root workspace di dalam distro (bind proot: filesDir/workspace). */
private const val GUEST_WS = "/root/workspace"

/** Probe IP terbaik dari wlan0 — `iproute2` dipasang setup toolchain. */
private const val ADB_IP_PROBE =
    "ip -4 addr show wlan0 | awk '/inet /{print $2}' | cut -d/ -f1"

/**
 * Status lintas-overlay: [running] menahan build kedua (single-flight) dan
 * [process] untuk tombol Batal — tetap utuh walau overlay ditutup.
 * (Bukan companion — fun komposabel top-level tak punya companion.)
 */
private object BuildState {
    @Volatile var running: Boolean = false
    @Volatile var process: Process? = null
    @Volatile var lastLogPath: File? = null
}

/**
 * Overlay tab Build: pilih proyek dari workspace → jalankan
 * `gradlew assembleDebug` → log mengalir ke file + pane → hasil APK.
 * Eksekusi memakai [execScope] (scope EngineScreen, di atas overlay)
 * sehingga build selamat saat overlay ditutup.
 */
@Composable
fun BuildScreen(
    engine: EngineManager,
    execScope: CoroutineScope,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fs = remember { VirtualFs(context) }
    val runner = remember {
        ExecRunner(context, DistroManager(context), engine.runtimeMode, TermuxBridge(context))
    }
    val prefs = remember { BuildPrefs(context) }

    var projects by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var runningBuild by remember { mutableStateOf(BuildState.running) }
    var setupRunning by remember { mutableStateOf(false) }
    var safTree by remember { mutableStateOf<String?>(null) }
    var adbIp by remember { mutableStateOf("") }
    var adbPairPort by remember { mutableStateOf("") }
    var adbCode by remember { mutableStateOf("") }
    var adbConnectPort by remember { mutableStateOf("") }
    var adbBusy by remember { mutableStateOf(false) }
    var logPath by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf<BuildSupport.ToolchainStatus?>(null) }
    var apks by remember { mutableStateOf<List<File>>(emptyList()) }
    val logLines = remember { mutableStateListOf<String>() }

    fun refreshApks() {
        val proj = selected ?: return
        apks = runCatching {
            File(fs.prootWorkspaceDir(), proj).walkTopDown()
                .filter {
                    it.isFile && it.extension == "apk" &&
                        it.path.contains("build${File.separator}outputs${File.separator}apk")
                }
                .sortedByDescending { it.lastModified() }
                .toList()
        }.getOrDefault(emptyList())
    }

    // Salin APK terbaru ke folder SAF pilihan (asli tetap — dibutuhkan gradle/ADB).
    fun copyApkToSaf(apk: File) {
        val tree = safTree ?: return
        val proj = selected ?: return
        val name = BuildSupport.apkOutName(proj, Date())
        val lp = BuildState.lastLogPath
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                WorkspaceExport.copyToTree(context, Uri.parse(tree), apk, name)
            }
            val baris = res.fold(
                onSuccess = { n -> I18n.s.buildCopied + n },
                onFailure = { e -> I18n.s.buildCopyFailed + (e.message ?: e.javaClass.simpleName) },
            )
            if (lp != null) {
                // Lewat file → pane (tail) ikut memperbarui + tetap terbaca AI.
                withContext(Dispatchers.IO) { runCatching { lp.appendText("$baris\n") } }
            } else {
                logLines.add(baris)
            }
        }
    }

    fun persistAdb() {
        scope.launch {
            runCatching { prefs.setAdbFields(AdbFields(adbIp, adbPairPort, adbConnectPort)) }
        }
    }

    fun appendAdbLine(s: String) {
        val lp = BuildState.lastLogPath
        if (lp != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        lp.parentFile?.mkdirs()
                        lp.appendText("$s\n")
                    }
                }
            }
        } else {
            logLines.add(s)
        }
    }

    // Output stdout+stderr jadi baris log; kode pairing tidak pernah ikut dicatat.
    fun runAdb(cmd: String, timeoutMs: Long, label: String) {
        if (adbBusy) return
        adbBusy = true
        scope.launch {
            try {
                val r = try {
                    runner.run(cmd, settings = ExecSettings(timeoutMs = timeoutMs))
                } catch (e: Exception) {
                    appendAdbLine("$label ✗ ${e.message}")
                    return@launch
                }
                appendAdbLine("$label exit=${r.exitCode}")
                (r.stdout + r.stderr).lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.isNotEmpty() }
                    .forEach { appendAdbLine("  $it") }
            } finally {
                adbBusy = false
            }
        }
    }

    fun installLatest() {
        val proj = selected ?: return
        val apk = apks.firstOrNull() ?: return
        val rel = runCatching { apk.relativeTo(File(fs.prootWorkspaceDir(), proj)).path }
            .getOrElse { apk.name }
        val guest = "$GUEST_WS/$proj/$rel"
        runAdb("adb install -r -d ${BuildSupport.shQuote(guest)}", 300_000, "adb install")
    }

    fun scanProjects() {
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { BuildSupport.findProjects(fs.prootWorkspaceDir()) }.getOrDefault(emptyList())
            }
            projects = found
            if (selected != null && selected !in found) selected = null
            if (selected == null && found.size == 1) selected = found[0]
            if (selected != null) withContext(Dispatchers.IO) { refreshApks() }
        }
    }

    fun startBuild() {
        val proj = selected ?: return
        if (BuildState.running) return
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val slug = BuildSupport.slugify(proj)
        val guestLog = "$GUEST_WS/build-logs/$slug-$ts.log"
        val logFile = File(fs.prootWorkspaceDir(), "build-logs/$slug-$ts.log")
        val cmd = "mkdir -p ${BuildSupport.shQuote("$GUEST_WS/build-logs")} && " +
            "cd ${BuildSupport.shQuote("$GUEST_WS/$proj")} && chmod +x gradlew ; " +
            "ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug --console=plain > " +
            BuildSupport.shQuote(guestLog) + " 2>&1"
        BuildState.running = true
        BuildState.lastLogPath = logFile
        runningBuild = true
        logPath = logFile
        execScope.launch {
            try {
                val res = try {
                    runner.run(
                        cmd,
                        settings = ExecSettings(timeoutMs = 1_800_000),
                        onProcess = { BuildState.process = it },
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            logFile.parentFile?.mkdirs()
                            logFile.appendText("${e.message}\n")
                        }
                    }
                    return@launch
                }
                // Ringkasan ikut ke file: overlay ditutup → buka lagi, terbaca utuh.
                withContext(Dispatchers.IO) {
                    runCatching { logFile.appendText(BuildSupport.summaryLine(res.exitCode)) }
                }
                if (res.exitCode == 0) {
                    withContext(Dispatchers.IO) { refreshApks() }
                    apks.firstOrNull()?.let { copyApkToSaf(it) }
                }
            } finally {
                BuildState.running = false
                BuildState.process = null
                runningBuild = false
            }
        }
    }

    fun cancelBuild() {
        val p = BuildState.process ?: return
        p.destroy()
        val lp = logPath ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { lp.appendText("dibatalkan (Batal)\n") } }
        }
    }

    // Siapkan toolchain (opsi A): tulis skrip → jalankan → ringkasan → re-probe.
    fun startSetup() {
        if (BuildState.running) return
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val guestLog = "$GUEST_WS/build-logs/setup-$ts.log"
        val logFile = File(fs.prootWorkspaceDir(), "build-logs/setup-$ts.log")
        val cmd = "mkdir -p ${BuildSupport.shQuote("$GUEST_WS/build-logs")} && bash " +
            BuildSupport.shQuote("$GUEST_WS/.arka/setup-toolchain.sh") +
            " > " + BuildSupport.shQuote(guestLog) + " 2>&1"
        BuildState.running = true
        BuildState.lastLogPath = logFile
        setupRunning = true
        logPath = logFile
        execScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { ToolchainSetup.ensureScript(fs.prootWorkspaceDir()) }.isSuccess
                }
                if (!ok) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            logFile.parentFile?.mkdirs()
                            logFile.appendText("gagal menulis setup-toolchain.sh\n")
                        }
                    }
                    return@launch
                }
                val res = try {
                    runner.run(
                        cmd,
                        settings = ExecSettings(timeoutMs = 1_800_000),
                        onProcess = { BuildState.process = it },
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            logFile.parentFile?.mkdirs()
                            logFile.appendText("${e.message}\n")
                        }
                    }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    runCatching { logFile.appendText(BuildSupport.summaryLine(res.exitCode)) }
                }
                val st = runCatching {
                    runner.run(ToolchainSetup.PROBE_CMD, settings = ExecSettings(timeoutMs = 15_000))
                }.getOrNull()
                status = st?.let { BuildSupport.parseToolchain(it.stdout) }
            } finally {
                BuildState.running = false
                BuildState.process = null
                setupRunning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        scanProjects()
        logPath = BuildState.lastLogPath
        safTree = runCatching { prefs.safTree() }.getOrNull()
        runCatching { prefs.adbFields() }.getOrNull()?.let { f ->
            adbIp = f.ip
            adbPairPort = f.pairPort
            adbConnectPort = f.connectPort
            if (f.ip.isBlank()) {
                val out = runCatching {
                    runner.run(ADB_IP_PROBE, settings = ExecSettings(timeoutMs = 15_000))
                }.getOrNull()?.stdout ?: ""
                val found = out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                if (!found.isNullOrEmpty()) {
                    adbIp = found
                    runCatching { prefs.setAdbFields(AdbFields(found, f.pairPort, f.connectPort)) }
                }
            }
        }
        val r = runCatching {
            runner.run(ToolchainSetup.PROBE_CMD, settings = ExecSettings(timeoutMs = 15_000))
        }
        status = r.map { BuildSupport.parseToolchain(it.stdout) }.getOrNull()
        // Termux/distro belum siap: alasan kegagalan probe wajib terlihat (design Cakupan).
        if (status == null) {
            r.exceptionOrNull()?.message?.let { appendAdbLine("toolchain probe ✗ $it") }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                safTree = uri.toString()
                withContext(Dispatchers.IO) { runCatching { prefs.setSafTree(safTree) } }
                apks.firstOrNull()?.let { copyApkToSaf(it) }
            }
        }
    }

    // Tail file log aktif: baca penuh saat effect mulai (resume), lalu byte baru.
    LaunchedEffect(logPath) {
        val f = logPath ?: return@LaunchedEffect
        val tailer = BuildSupport.LogTailer(f)
        logLines.clear()
        while (true) {
            logLines += tailer.readNew()
            if (logLines.size > 1000) logLines.removeRange(0, logLines.size - 1000)
            delay(500)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ClayHeader(
                title = I18n.s.buildTitle,
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
                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.buildProject, fontWeight = FontWeight.SemiBold)
                        if (projects.isEmpty()) {
                            Text("—", style = MaterialTheme.typography.bodySmall)
                        } else {
                            projects.forEach { name ->
                                TextButton(onClick = { selected = name; scanProjects() }) {
                                    Text(if (selected == name) "✓ $name" else name)
                                }
                            }
                        }
                        TextButton(onClick = { scanProjects() }) { Text(I18n.s.buildRefresh) }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.buildToolchain, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (status?.ready == true) I18n.s.buildToolchainOk
                            else I18n.s.buildToolchainMissing,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status?.ready == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (status?.ready == false) {
                            Button(onClick = { startSetup() }, enabled = !setupRunning) {
                                Text(I18n.s.buildSetup)
                            }
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { startBuild() },
                            enabled = selected != null && !runningBuild && !setupRunning,
                        ) {
                            Text(if (runningBuild) I18n.s.buildRunning else I18n.s.buildRun)
                        }
                        if (runningBuild || setupRunning) {
                            TextButton(onClick = { cancelBuild() }) {
                                Text(I18n.s.buildCancel, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.buildLog, fontWeight = FontWeight.SemiBold)
                        if (logLines.isNotEmpty()) {
                            Text(
                                logLines.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.buildResult, fontWeight = FontWeight.SemiBold)
                        if (apks.isEmpty()) {
                            Text(I18n.s.buildNoApk, style = MaterialTheme.typography.bodySmall)
                        } else {
                            val projDir = selected?.let { File(fs.prootWorkspaceDir(), it) }
                            apks.forEach { apk ->
                                val rel = projDir?.let { d ->
                                    runCatching { apk.relativeTo(d).path }.getOrNull()
                                } ?: apk.name
                                Text(rel, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(onClick = { treeLauncher.launch(null) }) { Text(I18n.s.buildPickFolder) }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.adbTitle, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = adbIp,
                            onValueChange = { adbIp = it },
                            label = { Text(I18n.s.adbIp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = adbPairPort,
                            onValueChange = { adbPairPort = it },
                            label = { Text(I18n.s.adbPairPort) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = adbCode,
                            onValueChange = { adbCode = it },
                            label = { Text(I18n.s.adbCode) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = adbConnectPort,
                            onValueChange = { adbConnectPort = it },
                            label = { Text(I18n.s.adbConnectPort) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    persistAdb()
                                    runAdb(
                                        "adb pair " +
                                            BuildSupport.shQuote("$adbIp:$adbPairPort") + " " +
                                            BuildSupport.shQuote(adbCode),
                                        20_000,
                                        "adb pair",
                                    )
                                },
                                enabled = !adbBusy && adbIp.isNotBlank() &&
                                    adbPairPort.isNotBlank() && adbCode.isNotBlank(),
                            ) { Text(I18n.s.adbPair) }
                            Button(
                                onClick = {
                                    persistAdb()
                                    runAdb(
                                        "adb connect " + BuildSupport.shQuote("$adbIp:$adbConnectPort"),
                                        20_000,
                                        "adb connect",
                                    )
                                },
                                enabled = !adbBusy && adbIp.isNotBlank() && adbConnectPort.isNotBlank(),
                            ) { Text(I18n.s.adbConnect) }
                        }
                        Text(
                            I18n.s.adbInstallWarn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = { installLatest() },
                            enabled = !adbBusy && apks.isNotEmpty(),
                        ) { Text(I18n.s.adbInstall) }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
