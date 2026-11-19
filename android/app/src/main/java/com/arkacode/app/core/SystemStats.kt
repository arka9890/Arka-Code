package com.arkacode.app.core

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Sampel pemakaian CPU dan RAM untuk kartu status di Pengaturan.
 *
 * CPU perangkat dibaca dari `/proc/stat` karena itu satu-satunya sumber tanpa
 * izin khusus. Bila baris itu tidak terbaca (kebijakan SELinux pada sebagian
 * perangkat), [cpuPercent] bernilai null dan kartu menampilkan "tidak
 * tersedia" alih-alih angka palsu.
 */
class SystemStats(context: Context) {

    private val am = context.applicationContext
        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** Sampel pertama hanya jadi titik acuan, jadi percent-nya belum ada. */
    private var lastBusy: Long = -1
    private var lastTotal: Long = -1

    fun sample(): SystemSample {
        val cpu = readCpuPercent()
        val mi = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(mi) }
        val used = (mi.totalMem - mi.availMem).coerceAtLeast(0L)
        return SystemSample(
            cpuPercent = cpu,
            ramUsedBytes = used,
            ramTotalBytes = mi.totalMem,
            appBytes = readAppBytes(),
        )
    }

    /**
     * Baris `cpu` di `/proc/stat`: user nice system idle iowait irq softirq steal.
     * `idle + iowait` dianggap menganggur; sisanya sibuk.
     */
    private fun readCpuPercent(): Int? = runCatching {
        val line = File("/proc/stat").useLines { lines ->
            lines.firstOrNull { it.startsWith("cpu ") }
        } ?: return@runCatching null
        val v = line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }
        if (v.size < 4) return@runCatching null
        val idle = v[3] + (v.getOrNull(4) ?: 0L)
        val total = v.sum()
        val prevBusy = lastBusy
        val prevTotal = lastTotal
        lastBusy = total - idle
        lastTotal = total
        if (prevTotal < 0 || total <= prevTotal) return@runCatching null
        val dTotal = total - prevTotal
        val dBusy = (total - idle) - prevBusy
        ((dBusy * 100) / dTotal).toInt().coerceIn(0, 100)
    }.getOrNull()

    /** PSS proses ini. `totalPss` selalu dalam kilobyte menurut dokumentasi Android. */
    private fun readAppBytes(): Long = runCatching {
        val pid = android.os.Process.myPid()
        val info = am.getProcessMemoryInfo(intArrayOf(pid))
        (info.firstOrNull()?.totalPss ?: 0) * 1024L
    }.getOrDefault(0L)
}

/** Satu pembacaan; [cpuPercent] null berarti angka tidak tersedia di perangkat ini. */
data class SystemSample(
    val cpuPercent: Int?,
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val appBytes: Long,
) {
    val ramPercent: Int
        get() = if (ramTotalBytes <= 0L) 0 else ((ramUsedBytes * 100) / ramTotalBytes).toInt()

    companion object {
        /** 3232 MiB → "3,2 GB". Satuan desimal supaya cocok dengan penyebutan di HP. */
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
            } else {
                String.format(java.util.Locale.US, "%.0f MB", mb)
            }
        }
    }
}
