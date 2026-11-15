package com.arkacode.app.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromString

/**
 * Penyimpanan memori (session + global tool) terenkripsi mengikuti pola
 * [SecureStore]: EncryptedSharedPreferences dengan MasterKey AES256_GCM/SIV,
 * file `arkacode_memory`. Data kompleks diserialisasi ke JSON sebelum disimpan.
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

    // ------------------------------------------------------------- global tool memory

    /** Simpan/Upsert memori tool global untuk toolName. */
    fun saveGlobalToolMemory(context: Context, memory: GlobalToolMemory) {
        runCatching {
            prefs(context).edit()
                .putString("global_tool_${memory.toolName}", json.encodeToString(memory))
                .apply()
        }
    }

    /** Muat memori tool global untuk toolName; null jika belum ada. */
    fun loadGlobalToolMemory(context: Context, toolName: String): GlobalToolMemory? = runCatching {
        val p = prefs(context)
        val s = p.getString("global_tool_$toolName", null)
        if (s.isNullOrBlank()) return@runCatching null
        json.decodeFromString(s)
    }.getOrNull()

    /** Muat semua memori tool global yang tersimpan. */
    fun loadAllGlobalToolMemory(context: Context): List<GlobalToolMemory> = runCatching {
        val p = prefs(context)
        p.all.keys
            .filter { it.startsWith("global_tool_") }
            .mapNotNull { key ->
                val toolName = key.removePrefix("global_tool_")
                loadGlobalToolMemory(context, toolName)
            }
    }.getOrNull() ?: emptyList()

    /**
     * Catat penggunaan satu tool ke memori global. `success` = apakah tool
     * selesai berhasil, `pattern` = ringkasan parameter (best-effort, boleh
     * null), `providerId`/`modelId` = siapa yang terakhir memakainya.
     */
    fun updateGlobalToolUsage(
        context: Context,
        toolName: String,
        success: Boolean,
        pattern: String? = null,
        providerId: String? = null,
        modelId: String? = null,
    ) {
        val existing = loadGlobalToolMemory(context, toolName)
            ?: GlobalToolMemory(
                toolName = toolName,
                lastUsedAt = System.currentTimeMillis(),
            )
        val updated = existing.copy(
            usageCount = existing.usageCount + 1,
            successCount = existing.successCount + (if (success) 1 else 0),
            lastUsedAt = System.currentTimeMillis(),
            commonPatterns = if (pattern.isNullOrBlank()) existing.commonPatterns
                else (existing.commonPatterns + pattern).distinct().takeLast(20),
            providerId = providerId ?: existing.providerId,
            modelId = modelId ?: existing.modelId,
        )
        saveGlobalToolMemory(context, updated)
    }
}
