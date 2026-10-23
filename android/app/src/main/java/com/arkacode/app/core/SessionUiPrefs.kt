package com.arkacode.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// internal: satu delegate untuk seluruh app (BuildPrefs memakai store yang sama)
internal val Context.uiPrefsStore by preferencesDataStore(name = "arkacode_ui")

/**
 * Override tampilan daftar sesi (F1+): ganti nama + sembunyikan.
 * Server v1.19 tidak menyediakan rename/hapus sesi, jadi keduanya murni
 * lokal — data di server tidak diubah/dihapus.
 */
class SessionUiPrefs(private val context: Context) {
    suspend fun titleOverrides(): Map<String, String> =
        context.uiPrefsStore.data.map { prefs ->
            buildMap {
                for ((k, v) in prefs.asMap()) {
                    if (k.name.startsWith("title:") && v is String) {
                        put(k.name.removePrefix("title:"), v)
                    }
                }
            }
        }.first()

    suspend fun setTitle(id: String, title: String) {
        context.uiPrefsStore.edit { it[stringPreferencesKey("title:$id")] = title }
    }

    suspend fun clearTitle(id: String) {
        context.uiPrefsStore.edit { it.remove(stringPreferencesKey("title:$id")) }
    }

    suspend fun hiddenIds(): Set<String> =
        context.uiPrefsStore.data.map { prefs ->
            prefs[stringPreferencesKey("hidden_sessions")]
                ?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }.first()

    suspend fun setHidden(id: String, hidden: Boolean) {
        context.uiPrefsStore.edit { prefs ->
            val cur = prefs[stringPreferencesKey("hidden_sessions")]
                ?.split("\n")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
            if (hidden) cur.add(id) else cur.remove(id)
            prefs[stringPreferencesKey("hidden_sessions")] = cur.joinToString("\n")
        }
    }

    suspend fun themeMode(): String? =
        context.uiPrefsStore.data.map { prefs ->
            prefs[stringPreferencesKey("theme_mode")]
        }.first()

    suspend fun setThemeMode(mode: String) {
        context.uiPrefsStore.edit { it[stringPreferencesKey("theme_mode")] = mode }
    }

    suspend fun langCode(): String? =
        context.uiPrefsStore.data.map { prefs ->
            prefs[stringPreferencesKey("lang_code")]
        }.first()

    suspend fun setLangCode(code: String) {
        context.uiPrefsStore.edit { it[stringPreferencesKey("lang_code")] = code }
    }

    // --- Aru-chan --------------------------------------------------------

    /**
     * Sesi opencode milik Buddy, dipakai ulang antar giliran dan antar buka
     * layar. Tanpa ini tiap kali membuka layar Buddy membuat sesi baru yang
     * menumpuk di daftar.
     */
    suspend fun buddySessionId(): String? =
        context.uiPrefsStore.data.map { prefs ->
            prefs[stringPreferencesKey("buddy_session")]
        }.first()

    suspend fun setBuddySessionId(id: String) {
        context.uiPrefsStore.edit { it[stringPreferencesKey("buddy_session")] = id }
    }

    // --- Pembaruan engine opencode (F-update) -------------------------------

    /** Auto-update engine saat boot; default aktif. */
    suspend fun autoUpdateEnabled(): Boolean =
        context.uiPrefsStore.data.map { prefs ->
            prefs[booleanPreferencesKey("engine_auto_update")] ?: true
        }.first()

    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        context.uiPrefsStore.edit { it[booleanPreferencesKey("engine_auto_update")] = enabled }
    }

    /** Epoch ms cek pembaruan terakhir (0 = belum pernah). */
    suspend fun lastUpdateCheck(): Long =
        context.uiPrefsStore.data.map { prefs ->
            prefs[longPreferencesKey("engine_last_check")] ?: 0L
        }.first()

    suspend fun setLastUpdateCheck(epochMs: Long) {
        context.uiPrefsStore.edit { it[longPreferencesKey("engine_last_check")] = epochMs }
    }

    // --- Runtime & distro ---------------------------------------------------

    /**
     * Runtime untuk eksekusi command: proot (default) atau Termux.
     */
    suspend fun runtimeMode(): RuntimeMode =
        context.uiPrefsStore.data.map { prefs ->
            RuntimeMode.from(prefs[stringPreferencesKey("runtime_mode")])
        }.first()

    suspend fun setRuntimeMode(mode: RuntimeMode) {
        context.uiPrefsStore.edit { it[stringPreferencesKey("runtime_mode")] = mode.name }
    }

    /**
     * Id distro yang dipakai untuk rootfs proot ("alpine-3.20", "custom", …).
     */
    suspend fun distroId(): String =
        context.uiPrefsStore.data.map { prefs ->
            prefs[stringPreferencesKey("distro_id")] ?: DistroCatalog.ALPINE_320.id
        }.first()

    suspend fun setDistroId(id: String) {
        context.uiPrefsStore.edit { it[stringPreferencesKey("distro_id")] = id }
    }

    /**
     * URL rootfs kustom (dipakai kalau distro = "custom").
     * Kosong = pakai berkas lokal lewat SAF.
     */
    suspend fun customRootfsUrl(): String =
        context.uiPrefsStore.data.map { prefs ->
            prefs[stringPreferencesKey("custom_rootfs_url")] ?: ""
        }.first()

    suspend fun setCustomRootfsUrl(url: String) {
        context.uiPrefsStore.edit { it[stringPreferencesKey("custom_rootfs_url")] = url.trim() }
    }
}
