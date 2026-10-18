package com.arkacode.app.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.BuddyVoice
import kotlinx.coroutines.launch

/**
 * Layar Arka Buddy.
 *
 * Tahap 1: wajah besar + pemilih keadaan, supaya tiap ekspresi bisa diperiksa
 * satu per satu sebelum tahap suara menyusul.
 * Tahap 2: kolom API key Gemini + kotak kalimat untuk mencoba TTS. Tombol
 * tahan-untuk-bicara (tahap 3) akan menggantikan kotak kalimat ini.
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
    var speaking by remember { mutableStateOf(false) }

    fun toast(msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

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
            subtitle = if (speaking) BuddyText.status(BuddyMood.TALKING)
            else BuddyText.status(mood),
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

        // Pratinjau: label panjang, jadi digulir mendatar alih-alih dipaksa
        // muat dalam lima kolom sempit.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BuddyMood.entries.forEach { m ->
                ClayPill(
                    text = BuddyText.moodLabel(m),
                    selected = m == mood,
                    onClick = { mood = m },
                )
            }
        }

        // API key: disimpan terenkripsi lewat SecureStore, tak pernah ditampilkan
        // lagi setelah diisi (kolom sengaja kosong, hanya untuk mengganti).
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(BuddyText.keyHint) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !speaking,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                if (key.isBlank()) return@TextButton
                BuddyVoice.saveKey(context, key)
                key = ""
                toast(BuddyText.keySaved)
            },
            enabled = !speaking,
        ) {
            Text(if (BuddyVoice.hasKey(context)) BuddyText.keySaved else BuddyText.keyMissing)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = sentence,
                onValueChange = { sentence = it },
                label = { Text(BuddyText.sayHint) },
                enabled = !speaking,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (!BuddyVoice.hasKey(context)) {
                        toast(BuddyText.keyMissing)
                        return@TextButton
                    }
                    val text = sentence
                    if (text.isBlank()) return@TextButton
                    scope.launch {
                        speaking = true
                        runCatching {
                            mood = BuddyMood.TALKING
                            BuddyVoice.play(BuddyVoice.synthesize(context, text))
                        }.onSuccess {
                            mood = BuddyMood.HAPPY
                        }.onFailure {
                            mood = BuddyMood.IDLE
                            toast(BuddyText.failed(it.message ?: "?"))
                        }
                        speaking = false
                    }
                },
                enabled = !speaking,
            ) {
                Text(BuddyText.speak)
            }
        }
    }
}
