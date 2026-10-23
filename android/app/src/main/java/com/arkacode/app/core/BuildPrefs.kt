package com.arkacode.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Data tab Build: tujuan folder SAF + field ADB wireless.
 * Kode pairing TIDAK pernah disimpan — hanya IP dan port.
 */
data class AdbFields(
    val ip: String = "",
    val pairPort: String = "",
    val connectPort: String = "",
)

class BuildPrefs(private val context: Context) {

    suspend fun safTree(): String? =
        context.uiPrefsStore.data.map { it[stringPreferencesKey("build:safTree")] }.first()

    suspend fun setSafTree(uri: String?) {
        context.uiPrefsStore.edit { prefs ->
            val k = stringPreferencesKey("build:safTree")
            if (uri == null) prefs.remove(k) else prefs[k] = uri
        }
    }

    suspend fun adbFields(): AdbFields =
        context.uiPrefsStore.data.map { p ->
            AdbFields(
                ip = p[stringPreferencesKey("adb:ip")] ?: "",
                pairPort = p[stringPreferencesKey("adb:pairPort")] ?: "",
                connectPort = p[stringPreferencesKey("adb:connectPort")] ?: "",
            )
        }.first()

    suspend fun setAdbFields(f: AdbFields) {
        context.uiPrefsStore.edit { p ->
            p[stringPreferencesKey("adb:ip")] = f.ip
            p[stringPreferencesKey("adb:pairPort")] = f.pairPort
            p[stringPreferencesKey("adb:connectPort")] = f.connectPort
        }
    }
}
