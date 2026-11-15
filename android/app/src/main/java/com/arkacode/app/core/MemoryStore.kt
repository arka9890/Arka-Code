package com.arkacode.app.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Penyimpanan memori (session + global) terenkripsi mengikuti pola
 * [SecureStore]: EncryptedSharedPreferences dengan MasterKey AES256_GCM/SIV,
 * file `arkacode_memory`. Data kompleks diserialisasi ke JSON sebelum disimpan.
 *
 * Memori global menyimpan entri kunci-nilai umum (bukan cuma tool): path folder,
 * lokasi Chrome, perintah CLI, hasil pencarian berkas, dan lain-lain. Setiap
 * entri melacak jumlah pencarian gagal/berhasil agar model baru bisa merujuk
 * tanpa re-test panjang.
 */
object MemoryStore {
    private const val FILE = "arkacode_memory"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun prefs(context: Context) = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ------------------------------------------------------------- session memory

    /** Simpan memori sesi (overwrite jika sudah ada untuk sessionId yang sama). */
    fun saveSessionMemory(context: Context, memory: SessionMemory) {
        runCatching {
            prefs(context).edit()
                .putString("session_${memory.sessionId}", json.encodeToString(memory))
                .apply()
        }
    }

    /** Muat memori sesi untuk sessionId; null jika belum pernah disimpan. */
    fun loadSessionMemory(context: Context, sessionId: String): SessionMemory? = runCatching {
        val p = prefs(context)
        val s = p.getString("session_$sessionId", null)
        if (s.isNullOrBlank()) return@runCatching null
        json.decodeFromString(s)
    }.getOrNull()

    /** Hapus memori sesi untuk sessionId. */
    fun deleteSessionMemory(context: Context, sessionId: String) {
        runCatching {
            prefs(context).edit().remove("session_$sessionId").apply()
        }
    }

    // ------------------------------------------------------------- global memory

    /** Simpan/Upsert entri memori global untuk key. */
    fun saveGlobalMemory(context: Context, entry: GlobalMemoryEntry) {
        runCatching {
            prefs(context).edit()
                .putString("global_${entry.category.name}_${entry.key}", json.encodeToString(entry))
                .apply()
        }
    }

    /** Muat entri memori global untuk key + category; null jika belum ada. */
    fun loadGlobalMemory(context: Context, key: String, category: MemoryCategory): GlobalMemoryEntry? = runCatching {
        val p = prefs(context)
        val s = p.getString("global_${category.name}_$key", null)
        if (s.isNullOrBlank()) return@runCatching null
        json.decodeFromString(s)
    }.getOrNull()

    /** Muat semua entri memori global yang tersimpan. */
    fun loadAllGlobalMemory(context: Context): List<GlobalMemoryEntry> = runCatching {
        val p = prefs(context)
        val result = mutableListOf<GlobalMemoryEntry>()
        for (key in p.all.keys) {
            if (key.startsWith("global_")) {
                val jsonStr = p.getString(key, null) ?: continue
                if (jsonStr.isNotBlank()) {
                    val entry: GlobalMemoryEntry = json.decodeFromString(jsonStr)
                    result.add(entry)
                }
            }
        }
        result
    }.getOrNull() ?: emptyList()

    /**
     * Catat hasil pencarian ke memori global. Jika ditemukan, entri baru dibuat
     * atau di-update dengan searchSuccessCount++. Jika gagal, searchFailCount++.
     * Setelah 2 kali gagal lalu sukses, entri dianggap "terbukti" (isProven).
     */
    fun recordSearchResult(context: Context, result: SearchResult) {
        val existing = loadGlobalMemory(context, result.key, result.category)
            ?: GlobalMemoryEntry(
                key = result.key,
                value = result.value,
                category = result.category,
                lastFoundAt = if (result.found) System.currentTimeMillis() else 0,
            )

        val updated = when {
            result.found -> existing.copy(
                value = result.value,
                category = result.category,
                searchSuccessCount = existing.searchSuccessCount + 1,
                lastFoundAt = System.currentTimeMillis(),
                commonPatterns = if (result.pattern.isNullOrBlank()) existing.commonPatterns
                    else (existing.commonPatterns + result.pattern).distinct().takeLast(20),
                providerId = result.providerId ?: existing.providerId,
                modelId = result.modelId ?: existing.modelId,
            )
            else -> existing.copy(
                searchFailCount = existing.searchFailCount + 1,
                providerId = result.providerId ?: existing.providerId,
                modelId = result.modelId ?: existing.modelId,
            )
        }
        saveGlobalMemory(context, updated)
    }

    /**
     * Catat penggunaan tool (untuk kompatibilitas dengan pelacakan tool yang
     * sudah ada di SessionRunRegistry). Dipanggil saat tool selesai.
     */
    fun recordToolUsage(
        context: Context,
        toolName: String,
        success: Boolean,
        pattern: String? = null,
        providerId: String? = null,
        modelId: String? = null,
    ) {
        recordSearchResult(context,
            SearchResult(
                key = toolName,
                value = toolName,
                category = MemoryCategory.Tool,
                found = success,
                pattern = pattern,
                providerId = providerId,
                modelId = modelId,
            ))
    }
}
