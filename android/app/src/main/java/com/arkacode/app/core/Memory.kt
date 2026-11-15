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
 * Memori global: pola penggunaan tools di seluruh sesi dan model. Model baru
 * yang belum pernah coba tool tertentu bisa merujuk ke sini untuk mengetahui
 * tool mana yang sering digunakan dan tingkat keberhasilannya, tanpa perlu
 * re-test panjang.
 */
@Serializable
data class GlobalToolMemory(
    val toolName: String,
    /** Jumlah kali tool ini dipanggil (seluruh sesi). */
    val usageCount: Int = 0,
    /** Jumlah kali tool ini sukses (status completed). */
    val successCount: Int = 0,
    val lastUsedAt: Long = 0,
    /** Pola parameter yang sering dipakai (max 20 entri terbaru). */
    val commonPatterns: List<String> = emptyList(),
    /** Provider yang terakhir menggunakan tool ini. */
    val providerId: String? = null,
    /** Model yang terakhir menggunakan tool ini. */
    val modelId: String? = null,
) {
    /** Tingkat keberhasitan tool (0.0 – 1.0), null jika belum pernah dipakai. */
    fun successRate(): Float? =
        if (usageCount == 0) null else successCount.toFloat() / usageCount
}
