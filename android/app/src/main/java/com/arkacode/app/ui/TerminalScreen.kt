package com.arkacode.app.ui

import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import org.json.JSONTokener

/** Fase sesi terminal. `Exited` membawa kode keluar shell. */
sealed interface TerminalState {
    data object Starting : TerminalState
    data object Running : TerminalState
    data class Exited(val code: Int) : TerminalState
}

/**
 * Tulis byte terminal ke xterm.js di [WebView].
 *
 * Base64 `NO_WRAP` wajib: `atob` di halaman menolak `-_` (varian URL_SAFE) dan
 * newline dari `DEFAULT` akan memutus literal string JS.
 */
internal fun WebView.termWrite(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    evaluateJavascript("termWrite(\"${Base64.encodeToString(bytes, Base64.NO_WRAP)}\")", null)
}

/** Sticky Ctrl/Alt: Ctrl memetakan karakter ke kontrol (`c` → 0x03), Alt menambah ESC. */
internal fun applyModifiers(bytes: ByteArray, ctrl: Boolean, alt: Boolean): ByteArray {
    if ((!ctrl && !alt) || bytes.isEmpty()) return bytes
    val first = bytes[0].toInt()
    val mapped = when {
        !ctrl -> bytes[0]
        first in 'a'.code..'z'.code -> (first and 0x1f).toByte()
        first in 0x40..0x5f -> (first and 0x1f).toByte()
        first == 0x3f -> 0x7f // Ctrl-? = DEL
        else -> bytes[0]
    }
    val out = ByteArray(bytes.size + if (alt) 1 else 0)
    var i = 0
    if (alt) out[i++] = 0x1b
    out[i++] = mapped
    bytes.copyInto(out, i, 1)
    return out
}

/**
 * Layar terminal: WebView xterm.js + baris tombol kunci.
 *
 * WebView dibuat per pemasangan layar (di-`remember`) — isi lama datang dari
 * [replay], jadi tidak ada WebView yang dipegang lintas Activity.
 *
 * Halaman memanggil `Bridge` saat memuat (fit pertama), karena itu
 * `addJavascriptInterface` dipasang sebelum `loadUrl`. `evaluateJavascript`
 * bersifat async: [replay] dikirim dari `onPageFinished`, potongan baru dari
 * [output] dikirim sesudahnya lewat [LaunchedEffect] agar urutannya terjaga.
 * Tidak ada buffering di sini — replay sudah mencakup output sebelum halaman siap.
 */
@Composable
fun TerminalScreen(
    state: TerminalState,
    distroLabel: String,
    replay: ByteArray,
    output: Flow<ByteArray>,
    onInput: (ByteArray) -> Unit,
    onResize: (Int, Int) -> Unit,
    onAck: (Int) -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Callback bisa berubah tiap recompose, sedangkan jembatan JS dibuat sekali.
    val inputNow = rememberUpdatedState(onInput)
    val resizeNow = rememberUpdatedState(onResize)
    val ackNow = rememberUpdatedState(onAck)
    val replayNow = rememberUpdatedState(replay)

    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }

    /** Kirim byte ke pty; sticky Ctrl/Alt lepas setelah satu kiriman. */
    fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val c = ctrl
        val a = alt
        ctrl = false
        alt = false
        inputNow.value(applyModifiers(bytes, c, a))
    }

    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(0xFF111111.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(
                TerminalBridge(
                    { send(it) },
                    { rows, cols -> resizeNow.value(rows, cols) },
                    { bytes -> ackNow.value(bytes) },
                ),
                "Bridge",
            )
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    pageReady = true
                    view.termWrite(replayNow.value)
                }
            }
            loadUrl("file:///android_asset/terminal/index.html")
        }
    }

    LaunchedEffect(webView, pageReady, output) {
        if (!pageReady) return@LaunchedEffect
        output.collect { chunk -> webView.termWrite(chunk) }
    }

    /** Tempel isi clipboard apa adanya (bukan satu karakter, jadi sticky tidak dipakai). */
    fun paste() {
        val text = clipboard.getText()?.text
        if (!text.isNullOrEmpty()) inputNow.value(text.toByteArray(Charsets.UTF_8))
    }

    /** Seleksi xterm hanya bisa dibaca lewat JS; hasilnya string JSON. */
    fun copySelection() {
        if (!pageReady) return
        webView.evaluateJavascript("termGetSelection()") { result ->
            val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            if (!text.isNullOrEmpty()) clipboard.setText(AnnotatedString(text))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                I18n.s.terminalTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (distroLabel.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    distroLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.destroy() },
            )

            if (state !is TerminalState.Running) {
                // Menutup scrim sekaligus menelan sentuhan, supaya ketikan tidak
                // masuk ke pty yang sudah mati.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
                ClayCard(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            when (state) {
                                TerminalState.Starting -> I18n.s.terminalStarting
                                is TerminalState.Exited -> I18n.s.terminalExited(state.code)
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRestart) { Text(I18n.s.terminalRestart) }
                            TextButton(onClick = onClose) { Text(I18n.s.terminalClose) }
                        }
                    }
                }
            }
        }

        // Baris tombol: hemat tinggi, bisa digeser bila layar sempit.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val keys = listOf(
                "Esc" to byteArrayOf(0x1b),
                "Tab" to byteArrayOf(0x09),
                "←" to "\u001b[D".toByteArray(),
                "↑" to "\u001b[A".toByteArray(),
                "↓" to "\u001b[B".toByteArray(),
                "→" to "\u001b[C".toByteArray(),
                "PgUp" to "\u001b[5~".toByteArray(),
                "PgDn" to "\u001b[6~".toByteArray(),
                "Ctrl-C" to byteArrayOf(0x03),
                "Ctrl-D" to byteArrayOf(0x04),
                "Ctrl-Z" to byteArrayOf(0x1a),
            )
            keys.forEach { (label, bytes) ->
                TextButton(
                    onClick = { send(bytes) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
            // Sticky: menyala sampai satu karakter terkirim.
            TextButton(
                onClick = { ctrl = !ctrl },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    "Ctrl",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ctrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (ctrl) FontWeight.Bold else FontWeight.Normal,
                )
            }
            TextButton(
                onClick = { alt = !alt },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    "Alt",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (alt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (alt) FontWeight.Bold else FontWeight.Normal,
                )
            }
            TextButton(
                onClick = { paste() },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(I18n.s.terminalPaste, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = { copySelection() },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(I18n.s.terminalCopy, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
