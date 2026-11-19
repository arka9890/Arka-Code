package com.arkacode.app.core


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
    private var total = 0L

    /**
     * Catat potongan; kembalikan nomor urut byte kumulatif setelah potongan ini
     * (offset akhir). Pemanggil memakai angka itu sebagai penanda: potongan
     * dengan offset akhir <= isi [snapshotWithTotal] sudah pasti ikut diputar
     * ulang, jadi tidak boleh dikirim lagi ke layar baru.
     */
    @Synchronized
    fun record(bytes: ByteArray): Long {
        if (bytes.isEmpty()) return total
        // Potongan raksasa dipangkas lebih dulu supaya deque tidak menyimpan
        // byte yang pasti langsung dibuang.
        val chunk = if (bytes.size > maxBytes) {
            bytes.copyOfRange(bytes.size - maxBytes, bytes.size)
        } else {
            bytes.copyOf()
        }
        chunks.addLast(chunk)
        size += chunk.size
        total += bytes.size
        while (size > maxBytes) {
            size -= chunks.removeFirst().size
        }
        return total
    }

    /** Salinan isi buffer sekarang; buffer tetap utuh untuk replay berikutnya. */
    @Synchronized
    fun snapshot(): ByteArray = snapshotWithTotal().first

    /**
     * Snapshot dan offset akhirnya diambil di bawah kunci yang sama, supaya
     * pemanggil tidak bisa memutar ulang byte yang juga masih mengantre di
     * kanal output (isi ganda) atau melewatkan potongan di antaranya.
     */
    @Synchronized
    fun snapshotWithTotal(): Pair<ByteArray, Long> {
        val bytes = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return bytes to total
    }
}
