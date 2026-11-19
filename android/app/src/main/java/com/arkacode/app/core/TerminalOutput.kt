package com.arkacode.app.core

import java.io.ByteArrayOutputStream

/**
 * Penampung output pty yang di-flush per potongan.
 *
 * Byte dikumpulkan sampai [maxBytes] lalu dikirim sebagai satu pesan ke
 * WebView, supaya satu frame tidak dipecah jadi ratusan pesan kecil.
 */
class TerminalOutputBuffer(private val maxBytes: Int = 64 * 1024) {

    private val out = ByteArrayOutputStream()

    /** Null selama belum penuh; isi buffer (lalu kosong) begitu mencapai [maxBytes]. */
    @Synchronized
    fun append(bytes: ByteArray, length: Int): ByteArray? {
        out.write(bytes, 0, length)
        if (out.size() < maxBytes) return null
        return drain()
    }

    /** Isi buffer lalu kosong. */
    @Synchronized
    fun drain(): ByteArray {
        val bytes = out.toByteArray()
        out.reset()
        return bytes
    }
}

/**
 * Ring buffer byte mentah untuk memutar ulang layar terminal.
 *
 * Dipakai saat WebView baru (mis. setelah rotasi) perlu isi layar terakhir
 * tanpa menunggu shell menggambar ulang. Byte tertua dibuang saat melebihi
 * [maxBytes]; urutan byte di dalam buffer tidak pernah diubah.
 */
class TerminalReplayBuffer(private val maxBytes: Int = 128 * 1024) {

    private val chunks = ArrayDeque<ByteArray>()
    private var size = 0

    @Synchronized
    fun record(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Potongan raksasa dipangkas lebih dulu supaya deque tidak menyimpan
        // byte yang pasti langsung dibuang.
        val chunk = if (bytes.size > maxBytes) {
            bytes.copyOfRange(bytes.size - maxBytes, bytes.size)
        } else {
            bytes.copyOf()
        }
        chunks.addLast(chunk)
        size += chunk.size
        while (size > maxBytes) {
            size -= chunks.removeFirst().size
        }
    }

    /** Salinan isi buffer sekarang; buffer tetap utuh untuk replay berikutnya. */
    @Synchronized
    fun snapshot(): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return bytes
    }
}
