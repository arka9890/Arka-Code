package com.arkacode.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arkacode.app.core.ContainerCatalog
import com.arkacode.app.core.ContainerManager
import com.arkacode.app.core.ContainerSpec
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
    /** Dipanggil setelah `engine.setDistro(...)`; pemilik sesi terminal menutup sesi lama. */
    onDistroChanged: () -> Unit = {},
    /** True selama terminal masih memakai distro aktif → hapus/pasang ulang ditolak. */
    terminalRunning: () -> Boolean = { false },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val uiPrefs = remember { SessionUiPrefs(context) }
    val termux = remember { TermuxBridge(context) }
    // `terminalRunning` tetap sumber kebenaran (rootfs bisa beda dari yang aktif),
    // tapi state terminal diumpankan sebagai state Compose supaya perubahan
    // sesi langsung memicu recomposisi tombol di bawah.
    val terminalState by com.arkacode.app.core.TerminalHost.state.collectAsState()
    val terminalBusy = remember(terminalState) { terminalRunning() }

    var runtime by remember { mutableStateOf(RuntimeMode.PROOT) }
    var distroId by remember { mutableStateOf(DistroCatalog.ALPINE_320.id) }
    var customUrl by remember { mutableStateOf("") }
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sizes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var prootReady by remember { mutableStateOf(true) }
    var termuxStatus by remember { mutableStateOf(TermuxBridge.Status(false, false, "")) }
    var progress by remember { mutableStateOf(0) }
    var progressLabel by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    /**
     * Id distro yang SEDANG diunduh — bukan flag global.
     *
     * Dulu ada satu `busy` untuk semua distro, jadi begitu satu distro
     * diunduh, tombol "Unduh & pasang" di SEMUA distro ikut mati dan user
     * merasa aplikasinya hang. Sekarang hanya kartu distro yang sedang
     * dikerjakan yang nonaktif; sisanya tetap bisa dipakai.
     */
    var installingId by remember { mutableStateOf<String?>(null) }
    /** Job unduhan yang sedang jalan — dipakai tombol "Batal". */
    var installJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /** Pesan error per distro (ditampilkan di kartunya, bukan di atas). */
    var installErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    /** Id distro yang sedang dihapus. */
    var removingId by remember { mutableStateOf<String?>(null) }
    /** Token++; incremented tiap unduhan baru (mencegah state lama menimpa baru). */
    var installToken by remember { mutableStateOf(0) }

    /**
     * State seksi **Tool containers** — sengaja dipisah dari state distro
     * karena container tidak memakai rootfs distro yang aktif: ia hidup di
     * `<filesDir>/containers` dan dipasang sekali untuk semua distro.
     */
    var installedContainerIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var containerSizes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var installingContainerId by remember { mutableStateOf<String?>(null) }
    var containerProgress by remember { mutableStateOf(0) }
    var containerProgressLabel by remember { mutableStateOf("") }
    var containerErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var removingContainerId by remember { mutableStateOf<String?>(null) }

    /** ABI perangkat — menentukan rootfs mana yang tersedia. */
    val deviceAbi = remember {
        android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }

    /** Manajer container (pembungkus File — ringan, aman di-remember per ABI). */
    val containerManager = remember(deviceAbi) { ContainerManager(context, deviceAbi) }

    /** ABI ini punya pasangan arsitektur Alpine? Kalau tidak, container tak bisa dipasang. */
    val containersSupported = remember(deviceAbi) { ContainerCatalog.archFor(deviceAbi) != null }

    /** Nama kelompok distro yang sudah diterjemahkan. */
    fun groupLabel(group: String): String = when (group) {
        "Lain-lain" -> I18n.s.distroGroupOther
        "Kustom" -> I18n.s.distroGroupCustom
        else -> group
    }

    /**
     * Status terpasang per distro — dicek cepat per folder, tanpa hitung ukuran rootfs.
     *
     * Sekalian menyegarkan status tool container (cek rootfs + shim per spec).
     * Semua pekerjaan file ada di IO; state Compose baru ditulis setelah
     * `withContext` kembali ke main thread.
     */
    fun refresh() {
        scope.launch {
            val (distroState, containerState) = withContext(Dispatchers.IO) {
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
                val containerReady = mutableSetOf<String>()
                val containerSz = mutableMapOf<String, String>()
                ContainerCatalog.ALL.forEach { spec ->
                    if (containerManager.isInstalled(spec)) {
                        containerReady += spec.id
                        containerSz[spec.id] = containerManager.sizeLabel(spec)
                    }
                }
                (ready to sizes) to (containerReady to containerSz)
            }
            installedIds = distroState.first
            sizes = distroState.second
            installedContainerIds = containerState.first
            containerSizes = containerState.second
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

    /**
     * Dialog izin runtime untuk `com.termux.permission.RUN_COMMAND`.
     *
     * Permission itu diiklankan Termux dengan protectionLevel "dangerous",
     * jadi manifest saja tidak cukup — user harus menyetujui dialog sistem.
     * Kalau dialognya disetujui, lanjut mendaftarkan app ke allowlist Termux
     * (langkah izin kedua) dan segarkan status.
     */
    val termuxPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            termux.requestPermissionIntent()?.let { runCatching { context.startActivity(it) } }
        }
        refresh()
    }

    /** Tombol "Izinkan di Termux": minta izin runtime dulu, lalu allowlist Termux. */
    fun requestTermuxPermission() {
        val granted = ContextCompat.checkSelfPermission(context, TermuxBridge.PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            termux.requestPermissionIntent()?.let { runCatching { context.startActivity(it) } }
            refresh()
        } else {
            termuxPermLauncher.launch(TermuxBridge.PERMISSION)
        }
    }

    fun selectRuntime(mode: RuntimeMode) {
        runtime = mode
        // Terapkan langsung ke engine yang hidup — tanpa ini mode baru baru
        // berlaku setelah app direstart (negara runtime hanya dipulihkan
        // sekali saat EngineScreen dibuka).
        engine.runtimeMode = mode
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { uiPrefs.setRuntimeMode(mode) } }
        }
        message = I18n.s.distroSaved
        onRuntimeChanged()
    }

    /**
     * Unduh & pasang rootfs distro.
     *
     * Per distro: kartu lain tetap interaktif. Error ditampilkan di kartu
     * distro yang gagal, dan unduhan bisa dibatalkan.
     *
     * CATATAN: fungsi lokal Kotlin tidak di-hoist — fungsi ini harus
     * dideklarasikan SEBELUM dipakai oleh fungsi lain di file ini.
     */
    fun install(spec: DistroSpec) {
        if (installingId != null) {
            message = I18n.s.distroInstallBusy
            return // sedang ada unduhan lain
        }
        if (!spec.supports(deviceAbi)) {
            installErrors = installErrors + (spec.id to I18n.s.distroNotAvailable)
            return
        }
        if (!prootReady) {
            installErrors = installErrors + (spec.id to I18n.s.distroProotMissing)
            return
        }
        installingId = spec.id
        progress = 0
        progressLabel = ""
        installErrors = installErrors - spec.id
        message = null
        // Token supaya hasil unduhan lama tidak menimpa state unduhan baru
        // (mis. user menekan Batal lalu langsung mengunduh lagi).
        val token = installToken + 1
        installToken = token

        installJob = scope.launch {
            val specToInstall = spec
            try {
                // Penting: teruskan spec-nya. Kalau tidak, rootfs yang
                // diunduh bisa masuk ke folder distro yang sedang aktif.
                val result = engine.setupDistro(
                    spec = specToInstall,
                    customRootfsUrl = if (specToInstall.custom) customUrl.ifBlank { null } else null,
                ) { p, label ->
                    // Callback-nya dipanggil dari thread IO (unduhan),
                    // sedangkan state Compose hanya boleh ditulis dari main
                    // thread — jadi lompat ke scope (dispatcher main).
                    scope.launch {
                        progress = p
                        progressLabel = label
                    }
                }
                val error = result.exceptionOrNull()
                if (error != null) {
                    installErrors = installErrors + (specToInstall.id to (error.message ?: "Gagal memasang distro."))
                } else {
                    // Baru pindah ke distro ini kalau instalasi BERHASIL —
                    // kalau gagal, distro aktif tidak boleh menunjuk rootfs
                    // yang tidak ada.
                    distroId = specToInstall.id
                    engine.setDistro(specToInstall)
                    withContext(Dispatchers.IO) {
                        runCatching { uiPrefs.setDistroId(specToInstall.id) }
                    }
                    message = "${specToInstall.label} siap. ${I18n.s.distroRestartHint}"
                    onRuntimeChanged()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (installToken == token) message = I18n.s.distroInstallCancelled
            } finally {
                if (installToken == token) {
                    installingId = null
                    installJob = null
                    progress = 0
                    progressLabel = ""
                }
                refresh()
            }
        }
    }

    /** Batalkan unduhan yang sedang jalan. */
    fun cancelInstall() {
        installToken = installToken + 1 // invalidasi hasil unduhan lama
        installJob?.cancel()
        installJob = null
        installingId = null
        progress = 0
        progressLabel = ""
        message = I18n.s.distroInstallCancelled
    }

    /**
     * Pasang satu tool container (python/node).
     *
     * Container dipasang di folder sendiri (`<filesDir>/containers/<id>`) dan
     * di-bind ke `/opt/containers` di distro mana pun, jadi rootfs distro aktif
     * tidak tersentuh. Setelah terpasang, engine perlu di-restart supaya PATH
     * container ikut masuk server (lihat `I18n.s.containerRestartHint`).
     *
     * Hanya satu container yang boleh dipasang sekaligus — tombol container
     * lain dinonaktifkan, bukan dihilangkan, supaya kartunya tidak melompat.
     */
    fun installContainer(spec: ContainerSpec) {
        if (installingContainerId != null) {
            message = I18n.s.containerInstallBusy
            return
        }
        if (!containersSupported) {
            containerErrors = containerErrors + (spec.id to I18n.s.distroNotAvailable)
            return
        }
        if (!prootReady) {
            containerErrors = containerErrors + (spec.id to I18n.s.distroProotMissing)
            return
        }
        installingContainerId = spec.id
        containerProgress = 0
        containerProgressLabel = ""
        containerErrors = containerErrors - spec.id
        message = null

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    containerManager.install(spec) { p, label ->
                        // Callback-nya datang dari thread IO (unduh/ekstrak),
                        // sedangkan state Compose hanya boleh ditulis dari main
                        // thread — jadi lompat ke scope (dispatcher main).
                        scope.launch {
                            containerProgress = p
                            containerProgressLabel = label
                        }
                    }
                }
                val error = result.exceptionOrNull()
                if (error != null) {
                    containerErrors = containerErrors +
                        (spec.id to (error.message ?: I18n.s.failedPrefix))
                } else {
                    message = I18n.s.containerRestartHint
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Dibatalkan (mis. layar ditinggal) — bukan kegagalan user-facing.
                throw e
            } finally {
                installingContainerId = null
                containerProgress = 0
                containerProgressLabel = ""
                refresh()
            }
        }
    }

    /** Hapus container + shim-nya; dikunci selama engine hidup (gating di UI). */
    fun removeContainer(spec: ContainerSpec) {
        if (removingContainerId != null) return
        removingContainerId = spec.id
        containerErrors = containerErrors - spec.id
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { containerManager.remove(spec, engine.isRunning()) }
                val error = result.exceptionOrNull()
                if (error != null) {
                    containerErrors = containerErrors +
                        (spec.id to (error.message ?: I18n.s.failedPrefix))
                }
            } finally {
                removingContainerId = null
                refresh()
            }
        }
    }

    /**
     * Jadikan distro ini distro aktif.
     *
     * Kalau distro belum terpasang, JANGAN langsung dipindah — user bisa
     * memilih distro yang rootfs-nya belum ada lalu diam-diam memicu
     * unduhan 150 MB. Jadi distro yang belum terpasang cukup memicu unduhan;
     * perpindahan ke "aktif" baru terjadi setelah unduhan sukses.
     */
    fun selectDistro(spec: DistroSpec) {
        if (spec.id !in installedIds) {
            install(spec)
            // install() sudah menandai distro ini sebagai sedang diunduh.
            if (installingId == spec.id) message = I18n.s.distroNotInstalledYet
            return
        }
        distroId = spec.id
        engine.setDistro(spec)
        // Sesi terminal milik distro lama; distro baru tidak boleh mewarisinya.
        onDistroChanged()
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { uiPrefs.setDistroId(spec.id) }
            }
        }
        message = I18n.s.distroSaved
        onRuntimeChanged()
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

                // Terminal masih memegang rootfs: hapus dan pasang ulang ditolak
                // sampai sesi ditutup dari layar terminal.
                if (terminalBusy) {
                    ClayCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            I18n.s.terminalDistroBusy,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp),
                        )
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
                                TextButton(onClick = { requestTermuxPermission() }) {
                                    Text(I18n.s.distroTermuxAllow)
                                }
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

                // --- Tool containers --------------------------------------------
                // Hanya untuk proot: runtime Termux tidak memakai bind container,
                // jadi memasang container di sana tidak ada gunanya.
                if (runtime == RuntimeMode.PROOT) {
                    Text(
                        I18n.s.containersTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    ContainerCatalog.ALL.forEach { spec ->
                        val isInstalled = spec.id in installedContainerIds
                        val isInstalling = spec.id == installingContainerId
                        val isRemoving = spec.id == removingContainerId
                        val error = containerErrors[spec.id]
                        val engineRunning = engine.isRunning()
                        ClayCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        spec.label,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        if (isInstalled) I18n.s.containerInstalled else I18n.s.containerNotInstalled,
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
                                    if (spec.approxSizeMb > 0) {
                                        Text(
                                            "~${spec.approxSizeMb} MB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    containerSizes[spec.id]?.let {
                                        Text(
                                            "· $it",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (!containersSupported) {
                                    Text(
                                        I18n.s.distroNotAvailable,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }

                                // Error pemasangan/penghapusan container ini (bukan global).
                                error?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }

                                if (isInstalling) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.width(16.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                containerProgressLabel.ifBlank { I18n.s.distroDownloading },
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        LinearProgressIndicator(
                                            progress = { containerProgress / 100f },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (isInstalled) {
                                        // Hapus dikunci selama engine hidup: shim yang
                                        // sedang dipakai sesi aktif akan menggantung.
                                        TextButton(
                                            onClick = { removeContainer(spec) },
                                            enabled = !engineRunning && !isRemoving &&
                                                installingContainerId == null,
                                        ) { Text(I18n.s.containerRemove) }
                                    } else {
                                        TextButton(
                                            onClick = { installContainer(spec) },
                                            enabled = prootReady && containersSupported &&
                                                !isInstalling && installingContainerId == null,
                                        ) { Text(I18n.s.containerInstall) }
                                    }
                                }
                                if (isInstalled && engineRunning) {
                                    Text(
                                        I18n.s.containerRemoveBusy,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                            val isInstalling = spec.id == installingId
                            val isRemoving = spec.id == removingId
                            val error = installErrors[spec.id]
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
                                            enabled = !isInstalling,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }

                                    // Error unduhan distro ini (bukan global).
                                    error?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }

                                    // Progres unduHAN INLINE di kartu distro ini,
                                    // jadi user langsung tahu apa yang sedang
                                    // diunduh tanpa harus scroll ke bawah.
                                    if (isInstalling) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.width(16.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    progressLabel.ifBlank { I18n.s.distroDownloading },
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                            LinearProgressIndicator(
                                                progress = { progress / 100f },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(
                                            onClick = { selectDistro(spec) },
                                            enabled = !isActive && supported && isInstalled && !isInstalling,
                                        ) { Text(I18n.s.distroActive) }
                                        if (isInstalling) {
                                            TextButton(onClick = { cancelInstall() }) {
                                                Text(I18n.s.cancel)
                                            }
                                        } else if (isInstalled) {
                                            // Sudah terpasang: tombol install
                                            // disembunyikan supaya rootfs yang
                                            // working tidak terhapus tak sengaja.
                                            // Kalau mau diperbarui, hapus dulu.
                                            TextButton(
                                                onClick = { install(spec) },
                                                enabled = prootReady && supported &&
                                                    !engine.isRunning() && !terminalBusy,
                                            ) { Text(I18n.s.distroReinstall) }
                                            TextButton(
                                                onClick = {
                                                    removingId = spec.id
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            DistroManager(context, spec).remove()
                                                        }
                                                        removingId = null
                                                        refresh()
                                                    }
                                                },
                                                enabled = !isRemoving && !engine.isRunning() && !terminalBusy,
                                            ) { Text(I18n.s.distroRemove) }
                                        } else {
                                            TextButton(
                                                onClick = { install(spec) },
                                                enabled = prootReady && supported &&
                                                    (!spec.custom || customUrl.isNotBlank()),
                                            ) { Text(I18n.s.distroInstall) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
