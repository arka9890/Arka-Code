package com.arkacode.app.ui

import android.util.Base64
import android.webkit.JavascriptInterface

/**
 * Jembatan JS -> Kotlin untuk tab Terminal (halaman `assets/terminal/index.html`).
 *
 * Argumen teks lewat base64 karena output/ketikan terminal adalah byte mentah:
 * round-trip lewat [String] bisa memotong urutan UTF-8 multi-byte.
 *
 * Metode kelas ini dipanggil dari JS lewat nama `Bridge` (lihat `addJavascriptInterface`).
 */
class TerminalBridge(
    private val onInput: (ByteArray) -> Unit,
    private val onResize: (Int, Int) -> Unit,
    private val onAck: (Int) -> Unit,
) {

    /** Ketikan pengguna: base64 dari byte UTF-8. */
    @JavascriptInterface
    fun input(base64: String) {
        onInput(Base64.decode(base64, Base64.DEFAULT))
    }

    /** Terminal berubah ukuran; `rows`/`cols` sudah dihitung oleh `fit()` di halaman. */
    @JavascriptInterface
    fun resize(rows: Int, cols: Int) {
        onResize(rows, cols)
    }

    /** xterm selesai menulis `bytes` byte, jadi buffer output boleh dibuang. */
    @JavascriptInterface
    fun ack(bytes: Int) {
        onAck(bytes)
    }
}
