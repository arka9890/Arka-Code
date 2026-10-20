package com.arkacode.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arkacode.app.core.BuddySpeech
import com.arkacode.app.core.BuddyAgent
import com.arkacode.app.core.BuddyVoice
import com.arkacode.app.core.SessionRunRegistry
import com.arkacode.app.core.SessionUiPrefs
import com.arkacode.app.core.TtsInstaller
import com.arkacode.app.net.OpenCodeClient
import kotlinx.coroutines.launch

/**
 * Layar Aru-chan.
 *
 * Tahap 1: wajah besar + pemilih keadaan, supaya tiap ekspresi bisa diperiksa
 * satu per satu.
 * Tahap 2: kotak kalimat untuk mencoba TTS. API key-nya pindah ke
 * Pengaturan.
 * Tahap 3: pemilih keadaan diganti tombol tahan-untuk-bicara (STT bawaan
 * Android). Keadaan kini digerakkan oleh alur nyata, bukan dipilih manual.
 * Tahap 4: hasil ucapan dikirim ke sesi opencode lewat [BuddyAgent] dan
 * balasan engine yang dibacakan. Sesi dipakai ulang antar giliran.
 */
@Composable
fun BuddyScreen(
    client: OpenCodeClient,
    runs: SessionRunRegistry,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agent = remember(client, runs) {
        BuddyAgent(client, runs, SessionUiPrefs(context))
    }

    var mood by remember { mutableStateOf(BuddyMood.IDLE) }
    var sentence by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }

    // Fungsi lokal harus dideklarasikan sebelum dipakai, jadi toast diletakkan
    // paling awal.
    fun toast(msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // Engine suara neural sudah terpasang? (dicek ulang tiap kali layar dibuka
    // dan tiap kali pemasangan selesai.)
    var neuralInstalled by remember { mutableStateOf(TtsInstaller.isInstalled(context)) }

    // Sedang menyiapkan installer (menyalin 148 MB) atau memasang?
    var preparingVoice by remember { mutableStateOf(false) }

    // Intent pemasangan yang menunggu izin "pasang dari sumber tak dikenal".
    var pendingInstall by remember { mutableStateOf<Intent?>(null) }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        neuralInstalled = TtsInstaller.isInstalled(context)
        preparingVoice = false
        if (neuralInstalled) TtsInstaller.cleanup(context)
    }

    // Izin "pasang aplikasi dari sumber tak dikenal" diberikan dulu; setelah
    // diizinkan, lanjutkan pemasangan yang tadi tertunda.
    val grantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val intent = pendingInstall
        pendingInstall = null
        if (intent != null && TtsInstaller.canInstallPackages(context)) {
            installLauncher.launch(intent)
        } else {
            preparingVoice = false
            toast(BuddyText.installPermissionDenied)
        }
    }

    /**
     * Pasang engine suara neural.
     *
     * Penyalinan installer 148 MB butuh waktu, jadi dilakukan di thread IO
     * (di luar thread UI) dengan umpan balik "menyiapkan…".
     */
    fun installNeuralVoice() {
        if (preparingVoice) return
        preparingVoice = true
        scope.launch {
            TtsInstaller.prepareInstall(context)
                .onSuccess { intent ->
                    if (TtsInstaller.canInstallPackages(context)) {
                        installLauncher.launch(intent)
                    } else {
                        pendingInstall = intent
                        grantLauncher.launch(TtsInstaller.unknownSourcesIntent(context))
                    }
                }
                .onFailure { error ->
                    preparingVoice = false
                    toast(BuddyText.installFailed(error.message ?: "?"))
                }
        }
    }

    // Buang installer yang tak terpakai (engine-nya sudah terpasang).
    LaunchedEffect(Unit) { TtsInstaller.cleanup(context) }

    // SpeechRecognizer harus dibebaskan saat layar ditutup, kalau tidak
    // mikrofon tetap menyala di latar belakang. Di sini pakai cancel() karena
    // tidak ada yang menunggu hasilnya lagi. TTS ikut dilepas agar engine tidak
    // nyangkut.
    DisposableEffect(Unit) {
        onDispose {
            BuddySpeech.destroy()
            BuddyVoice.destroy()
        }
    }

    /** Bacakan teks. Mengatur `mood`, bukan `busy` — pemanggil yang memegang. */
    suspend fun speakNow(text: String) {
        runCatching {
            mood = BuddyMood.TALKING
            BuddyVoice.speak(context, text)
        }.onSuccess {
            mood = BuddyMood.HAPPY
        }.onFailure {
            mood = BuddyMood.IDLE
            toast(BuddyText.failed(it.message ?: "?"))
        }
    }

    fun speak(text: String) {
        scope.launch {
            busy = true
            speakNow(text)
            busy = false
        }
    }

    /**
     * Kirim hasil ucapan ke engine, lalu bacakan balasannya.
     *
     * Tahap sebelumnya Buddy hanya mengulangi apa yang terdengar — supaya STT
     * bisa diperiksa tanpa menunggu jalur engine. Sekarang jalurnya sudah
     * nyambung.
     */
    fun answer(heard: String) {
        scope.launch {
            busy = true
            mood = BuddyMood.THINKING
            runCatching { agent.ask(heard) }
                .onSuccess { reply -> speakNow(reply) }
                .onFailure {
                    mood = BuddyMood.IDLE
                    toast(BuddyText.askFailed(it.message ?: "?"))
                }
            busy = false
        }
    }

    fun startListening() {
        if (busy) return
        listening = true
        mood = BuddyMood.LISTENING
        BuddySpeech.start(
            context = context,
            onText = { heard ->
                listening = false
                if (heard.isNullOrBlank()) {
                    mood = BuddyMood.IDLE
                    toast(BuddyText.nothingHeard)
                } else {
                    sentence = heard
                    answer(heard)
                }
            },
            onFailed = { code ->
                listening = false
                mood = BuddyMood.IDLE
                toast(BuddySpeech.errorMessage(code))
            },
        )
    }

    fun endListening() {
        if (!listening) return
        // Lembut: biarkan recognizer mengirim hasil terakhirnya lewat onResults.
        // Memakai cancel() di sini akan membuang hasil yang sudah terekam.
        BuddySpeech.stopListening()
    }

    // Izin diminta saat tombol ditekan pertama kali, bukan saat layar dibuka —
    // meminta izin tanpa konteks membuat orang menolaknya.
    //
    // Kalau disetujui kita TIDAK langsung mulai mendengarkan: pengguna sudah
    // melepas tombol selama dialog izin tampil, jadi tak ada yang akan
    // menghentikannya lagi.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toast(BuddyText.holdHint)
        else toast(BuddyText.micDenied)
    }

    fun holdPressed() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ClayHeader(
            title = BuddyText.title,
            subtitle = when {
                listening -> BuddyText.status(BuddyMood.LISTENING)
                busy -> BuddyText.status(BuddyMood.TALKING)
                else -> BuddyText.status(mood)
            },
            leading = { ClayIconBadge(Icons.Default.Face) },
            actions = { TextButton(onClick = onBack) { Text(I18n.s.close) } },
        )

        ClayCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Figur humanoid butuh tinggi (kepala + tubuh + kaki), jadi
                // canvas mengisi kartu, bukan kotak persegi tetap.
                BuddyFace(mood = mood, modifier = Modifier.fillMaxSize())
            }
        }

        // Tombol tahan-untuk-bicara: tekan lalu tahan, lepas untuk selesai.
        //
        // Digambar sendiri alih-alih memakai ClayPill, karena ClayPill
        // membawa Modifier.clickable di dalamnya. Dalam Compose gesture anak
        // diproses lebih dulu, jadi clickable itu akan menelan event tekan dan
        // detectTapGestures di induk tidak pernah menerimanya.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ClayShapePill)
                .background(
                    if (listening) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .pointerInput(busy) {
                    detectTapGestures(
                        onPress = {
                            holdPressed()
                            tryAwaitRelease()
                            endListening()
                        }
                    )
                }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    listening -> BuddyText.status(BuddyMood.LISTENING)
                    busy -> BuddyText.status(BuddyMood.THINKING)
                    else -> BuddyText.holdHint
                },
                color = if (listening) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }

        // Jalur manual: ketik kalimat lalu dengarkan (untuk mencoba suara tanpa
        // lewat STT). TTS kini lokal, jadi tidak butuh API key.
        OutlinedTextField(
            value = sentence,
            onValueChange = { sentence = it },
            label = { Text(BuddyText.sayHint) },
            enabled = !busy && !listening,
            modifier = Modifier.fillMaxWidth(),
        )
        // TTS kini lokal (bawaan Android) — tidak butuh API key lagi, jadi tidak
        // ada gate `hasKey` di sini.
        TextButton(
            onClick = {
                if (sentence.isBlank()) return@TextButton
                speak(sentence)
            },
            enabled = !busy && !listening,
        ) {
            Text(BuddyText.speak)
        }

        // Engine & voice yang sedang dipakai. Berguna saat suaranya tak
        // sesuai harapan: dari sini kelihatan engine neural yang dipakai atau
        // TTS bawaan sistem.
        Text(
            BuddyVoice.voiceInfo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Engine suara neural (sherpa-onnx) ikut dibundel di dalam APK ini, jadi
        // tak ada yang perlu diunduh — tinggal dipasang sekali. Setelah
        // terpasang, Aru-chan berbunyi neural: 10 suara Indonesia, tetap offline.
        if (!neuralInstalled) {
            OutlinedButton(
                onClick = { installNeuralVoice() },
                enabled = !busy && !listening && !preparingVoice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (preparingVoice) BuddyText.preparingVoice
                    else BuddyText.installNeuralVoice
                )
            }
            Text(
                BuddyText.neuralVoiceHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
