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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
    // Kolektor output dan `onPageFinished` bisa menyentuh WebView tepat
    // setelah `onRelease`-nya (rotasi/tutup). `evaluateJavascript` di WebView
    // yang sudah dihancurkan melempar IllegalStateException yang mematikan
    // app, jadi jalur ini dibungkus dan gagalnya diabaikan.
    runCatching {
        evaluateJavascript("termWrite(\"${Base64.encodeToString(bytes, Base64.NO_WRAP)}\")", null)
    }
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
    /** Alasan terminal tidak bisa dipakai (mis. runtime Termux); menimpa pesan status. */
    notice: String? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Pty mati: tombol kirim dinonaktifkan supaya ketikan tidak masuk ke sesi mati.
    val live = state is TerminalState.Running

    // Callback bisa berubah tiap recompose, sedangkan jembatan JS dibuat sekali.
    val inputNow = rememberUpdatedState(onInput)
    val resizeNow = rememberUpdatedState(onResize)
    val ackNow = rememberUpdatedState(onAck)
    val replayNow = rememberUpdatedState(replay)
    // Flow `output` boleh berganti identitas tiap recompose (mis. `receiveAsFlow()`
    // inline). Kolektor tidak boleh ikut restart — potongan yang sudah diambil
    // kolektor lama bisa hilang diam-diam.
    val outputNow = rememberUpdatedState(output)

    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }

    /** Karakter (ketikan xterm, aksi karakter): sticky Ctrl/Alt dipakai lalu lepas. */
    fun sendChar(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val c = ctrl
        val a = alt
        ctrl = false
        alt = false
        inputNow.value(applyModifiers(bytes, c, a))
    }

    /** Tombol kunci non-karakter (Esc, Tab, panah, PgUp/PgDn): apa adanya, sticky tetap menyala. */
    fun sendKey(bytes: ByteArray) {
        if (bytes.isNotEmpty()) inputNow.value(bytes)
    }

    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(0xFF111111.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(
                TerminalBridge(
                    { sendChar(it) },
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

    // `output` sengaja tidak jadi key: identitas Flow tidak boleh me-restart
    // kolektor. `rememberUpdatedState` memberi flow terbaru tanpa restart.
    LaunchedEffect(webView, pageReady) {
        if (!pageReady) return@LaunchedEffect
        outputNow.value.collect { chunk -> webView.termWrite(chunk) }
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

    // Latar solid + padding inset aman: overlay ini berada di atas layar yang
    // masih terisi (edge-to-edge). Tanpa ini baris tombol bawah tenggelam
    // di balik bilah navigasi sentuh dan header menutupi kartu di belakangnya.
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
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
            Spacer(Modifier.weight(1f))
            // Status bar tipis: Restart/Tutup juga ada saat shell hidup, supaya
            // "Tutup dulu baru hapus distro" benar-benar bisa dilakukan.
            TextButton(onClick = onRestart, enabled = state is TerminalState.Running) {
                Text(I18n.s.terminalRestart)
            }
            TextButton(onClick = onClose, enabled = state is TerminalState.Running) {
                Text(I18n.s.terminalClose)
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
                onRelease = {
                    // Lepas jalur masuk JS sebelum destroy: `onPageFinished` yang
                    // terlambat dan kolektor `output` tidak boleh lagi memanggil
                    // `evaluateJavascript` di WebView yang sudah mati.
                    // WebViewClient yang tidak melakukan apa pun: client asli
                    // masih bisa membaca state dari objek ini, tapi callback-nya
                    // tidak lagi ditujukan ke WebView yang sudah mati.
                    it.webViewClient = object : WebViewClient() {}
                    runCatching { it.removeJavascriptInterface("Bridge") }
                    it.destroy()
                },
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
                            notice ?: when (state) {
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
            // Kunci non-karakter: tidak memakai/mematikan sticky Ctrl/Alt.
            val keys = listOf(
                "Esc" to byteArrayOf(0x1b),
                "Tab" to byteArrayOf(0x09),
                "←" to "\u001b[D".toByteArray(),
                "↑" to "\u001b[A".toByteArray(),
                "↓" to "\u001b[B".toByteArray(),
                "→" to "\u001b[C".toByteArray(),
                "PgUp" to "\u001b[5~".toByteArray(),
                "PgDn" to "\u001b[6~".toByteArray(),
            )
            keys.forEach { (label, bytes) ->
                TextButton(
                    onClick = { sendKey(bytes) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(36.dp),
                    enabled = live,
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
            // Kontrol karakter: dihitung sebagai satu karakter (memakai sticky).
            val controlKeys = listOf(
                "Ctrl-C" to byteArrayOf(0x03),
                "Ctrl-D" to byteArrayOf(0x04),
                "Ctrl-Z" to byteArrayOf(0x1a),
            )
            controlKeys.forEach { (label, bytes) ->
                TextButton(
                    onClick = { sendChar(bytes) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(36.dp),
                    enabled = live,
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
            // Sticky: menyala sampai satu karakter terkirim.
            TextButton(
                onClick = { ctrl = !ctrl },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(36.dp),
                enabled = live,
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
                enabled = live,
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
                enabled = live,
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
