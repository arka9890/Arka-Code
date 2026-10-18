package com.arkacode.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arkacode.app.core.BuddySpeech
import com.arkacode.app.core.BuddyVoice
import kotlinx.coroutines.launch

/**
 * Layar Arka Buddy.
 *
 * Tahap 1: wajah besar + pemilih keadaan, supaya tiap ekspresi bisa diperiksa
 * satu per satu.
 * Tahap 2: kolom API key Gemini + kotak kalimat untuk mencoba TTS.
 * Tahap 3: pemilih keadaan diganti tombol tahan-untuk-bicara (STT bawaan
 * Android). Keadaan kini digerakkan oleh alur nyata, bukan dipilih manual.
 */
@Composable
fun BuddyScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mood by remember { mutableStateOf(BuddyMood.IDLE) }
    var key by remember { mutableStateOf("") }
    var sentence by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }

    fun toast(msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // SpeechRecognizer harus dibebaskan saat layar ditutup, kalau tidak
    // mikrofon tetap menyala di latar belakang.
    DisposableEffect(Unit) {
        onDispose { BuddySpeech.stop() }
    }

    fun speak(text: String) {
        scope.launch {
            busy = true
            runCatching {
                mood = BuddyMood.TALKING
                BuddyVoice.play(BuddyVoice.synthesize(context, text))
            }.onSuccess {
                mood = BuddyMood.HAPPY
            }.onFailure {
                mood = BuddyMood.IDLE
                toast(BuddyVoice.failed(it.message ?: "?"))
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
                    // Tahap 4 akan mengganti baris ini dengan mengirim heard ke
                    // sesi opencode; sementara Buddy hanya mengulanginya supaya
                    // hasil STT bisa diperiksa.
                    speak(heard)
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
        BuddySpeech.stop()
        listening = false
        mood = BuddyMood.IDLE
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
                BuddyFace(mood = mood, modifier = Modifier.size(260.dp))
            }
        }

        // Tombol tahan-untuk-bicara: tekan lalu tahan, lepas untuk selesai.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(busy) {
                    detectTapGestures(
                        onPress = {
                            holdPressed()
                            tryAwaitRelease()
                            endListening()
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            ClayPill(
                text = if (listening) BuddyText.status(BuddyMood.LISTENING) else BuddyText.holdHint,
                selected = listening,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // API key: disimpan terenkripsi lewat SecureStore, tak pernah ditampilkan
        // lagi setelah diisi (kolom sengaja kosong, hanya untuk mengganti).
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(BuddyText.keyHint) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy && !listening,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                if (key.isBlank()) return@TextButton
                BuddyVoice.saveKey(context, key)
                key = ""
                toast(BuddyText.keySaved)
            },
            enabled = !busy && !listening,
        ) {
            Text(if (BuddyVoice.hasKey(context)) BuddyText.keySaved else BuddyText.keyMissing)
        }

        OutlinedTextField(
            value = sentence,
            onValueChange = { sentence = it },
            label = { Text(BuddyText.sayHint) },
            enabled = !busy && !listening,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                if (!BuddyVoice.hasKey(context)) {
                    toast(BuddyText.keyMissing)
                    return@TextButton
                }
                if (sentence.isBlank()) return@TextButton
                speak(sentence)
            },
            enabled = !busy && !listening,
        ) {
            Text(BuddyText.speak)
        }
    }
}
