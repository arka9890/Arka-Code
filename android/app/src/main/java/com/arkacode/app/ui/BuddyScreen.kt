package com.arkacode.app.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Layar Arka Buddy.
 *
 * Tahap 1: wajah besar + pemilih keadaan, supaya tiap ekspresi bisa diperiksa
 * satu per satu sebelum tahap suara menyusul. Tombol tahan-untuk-bicara akan
 * menggantikan baris pemilih ini pada tahap suara.
 */
@Composable
fun BuddyScreen(
    onBack: () -> Unit,
) {
    var mood by remember { mutableStateOf(BuddyMood.IDLE) }

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
            subtitle = BuddyText.status(mood),
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
    }
}
