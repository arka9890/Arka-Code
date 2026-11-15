package com.arkacode.app.core

import com.arkacode.app.net.ModelRef
import kotlinx.serialization.Serializable

/**
 * Memori lokal per sesi: menyimpan konteks percakapan (model terakhir yang
 * dipakai, agent, jumlah pesan, ringkasan, topik kunci) agar context tidak
 * hilang saat ganti model di dalam sesi yang sama. Disimpan terenkripsi di
 * [MemoryStore] dan dimuat ulang setiap kali layar chat dibuka untuk sessionId
 * tersebut.
 */
@Serializable
data class SessionMemory(
    val sessionId: String,
    /** Model terakhir dipakai di sesi ini (providerID + modelID). */
    val lastModelRef: ModelRef? = null,
    /** Agent terakhir dipakai di sesi ini. */
    val lastAgentId: String? = null,
    /** Jumlah pesan yang sudah dipertukarkan di sesi ini. */
    val messageCount: Int = 0,
    /** Ringkasan singkat percakapan (best-effort) untuk konteks model baru. */
    val lastSummary: String? = null,
    /** Topik kunci yang telah dibahas di sesi ini. */
    val keyTopics: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Kategori entri memori global.
 */
enum class MemoryCategory {
    /** Tool yang dipanggil agent (mis. readFile, writeFile, buildApk). */
    Tool,
    /** Path folder atau file yang sering dicari (mis. output build APK, lokasi Chrome). */
    Path,
    /** Perintah shell / CLI yang berhasil gagal/berhasil. */
    Command,
    /** Berkas atau resource yang ditemukan setelah pencarian. */
    File,
    /** Entri lain yang tidak masuk kategori di atas. */
    Other,
}

/**
 * Memori global: entri kunci-nilai yang tersimpan di seluruh sesi dan model.
 * Model baru yang belum pernah mencoba sesuatu bisa merujuk ke sini untuk
 * mengetahui path / perintah / tool mana yang sering digunakan dan tingkat
 * keberhasilannya, tanpa perlu re-test panjang.
 *
 * [key] adalah kunci pencarian (mis. nama tool, path folder, perintah).
 * [value] adalah isi yang ditemukan (mis. path lengkap, hasil perintah).
 * [category] menandai jenis entri.
 * [searchFailCount] = berapa kali pencarian gagal sebelum ditemukan.
 * [searchSuccessCount] = berapa kali pencarian sukses.
 * [lastFoundAt] = timestamp terakhir ditemukan.
 * [commonPatterns] = pola parameter yang sering dipakai.
 * [providerId]/[modelId] = siapa yang terakhir menggunakan entri ini.
 */
@Serializable
data class GlobalMemoryEntry(
    val key: String,
    val value: String,
    val category: MemoryCategory = MemoryCategory.Other,
    val searchFailCount: Int = 0,
    val searchSuccessCount: Int = 0,
    val lastFoundAt: Long = 0,
    val commonPatterns: List<String> = emptyList(),
    val providerId: String? = null,
    val modelId: String? = null,
) {
    /** Tingkat keberhasitan pencarian (0.0 – 1.0), null jika belum pernah dicoba. */
    fun successRate(): Float? =
        val total = searchFailCount + searchSuccessCount
        if (total == 0) null else searchSuccessCount.toFloat() / total

    /** Apakah entri ini sudah \"terbukti\" (minimal 2 kali gagal lalu sukses). */
    fun isProven(): Boolean =
        searchFailCount >= 2 && searchSuccessCount >= 1
}

/**
 * Hasil pencarian yang akan dicatat ke memori global.
 */
data class SearchResult(
    val key: String,
    val value: String,
    val category: MemoryCategory = MemoryCategory.Other,
    val found: Boolean = true,
    val pattern: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
)
