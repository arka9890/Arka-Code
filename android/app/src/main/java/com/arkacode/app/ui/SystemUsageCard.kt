package com.arkacode.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.arkacode.app.core.SystemSample
import com.arkacode.app.core.SystemStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Rentang muat ulang: cukup sering untuk terasa hidup, cukup lambat untuk hemat baterai. */
private const val INTERVAL_MS = 2_000L

/**
 * Kartu pemakaian CPU dan RAM di Pengaturan.
 *
 * Bar dan teks ditampilkan bersamaan supaya angka tidak hanya dibedakan lewat
 * warna. Bar sengaja tanpa animasi: perubahan nilai sudah cukup terlihat, dan
 * pengguna yang mematikan animasi tidak kehilangan informasi apa pun.
 */
@Composable
fun SystemUsageCard(
    stats: SystemStats,
    modifier: Modifier = Modifier,
) {
    var sample by remember { mutableStateOf<SystemSample?>(null) }

    LaunchedEffect(stats) {
        while (true) {
            // /proc/stat dan ActivityManager adalah I/O; jangan di main thread.
            sample = withContext(Dispatchers.IO) { stats.sample() }
            delay(INTERVAL_MS)
        }
    }

    ClayCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                I18n.s.systemUsageTitle,
                fontWeight = FontWeight.SemiBold,
            )

            val current = sample
            if (current == null) {
                Text(
                    I18n.s.usageUnavailable,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // CPU perangkat. null = /proc/stat tidak terbaca di perangkat ini.
            UsageRow(
                label = I18n.s.cpuUsageLabel,
                progress = (current.cpuPercent ?: 0) / 100f,
                value = current.cpuPercent?.let { "$it%" } ?: I18n.s.usageUnavailable,
                muted = current.cpuPercent == null,
            )

            UsageRow(
                label = I18n.s.ramUsageLabel,
                progress = current.ramPercent / 100f,
                value = "${SystemSample.formatBytes(current.ramUsedBytes)} / " +
                    SystemSample.formatBytes(current.ramTotalBytes),
                muted = false,
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    I18n.s.appMemoryLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    SystemSample.formatBytes(current.appBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Satu baris: label, bar, angka. Label dipakai bersama bar sebagai penanda kuantitas. */
@Composable
private fun UsageRow(
    label: String,
    progress: Float,
    value: String,
    muted: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(6.dp),
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
