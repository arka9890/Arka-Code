package com.arkacode.app.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Pemasangan TTS engine sherpa-onnx (Bahasa Indonesia) dari dalam Arkacode.
 *
 * APK engine ikut dibundel di dalam APK Arkacode (lihat task Gradle
 * `downloadTtsEngineApk`), jadi tidak ada berkas yang perlu diunduh terpisah:
 * model neural-nya (Supertonic 3, 10 suara) sudah ada di dalam APK itu.
 *
 * Pemasangan sendiri tetap butuh persetujuan pengguna — Android tidak
 * mengizinkan aplikasi memasang APK lain diam-diam, dan pengguna harus
 * mengizinkan "pasang aplikasi dari sumber tak dikenal" lebih dulu.
 */
object TtsInstaller {

    /** Nama paket engine sherpa-onnx. */
    const val PACKAGE = "com.k2fsa.sherpa.onnx.tts.engine"

    /** Nama asset installer di dalam APK Arkacode. */
    const val ASSET_NAME = "tts-engine.apk"

    /** Already terpasang? */
    fun isInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        }.getOrDefault(false)

    /**
     * Siapkan berkas APK engine di cache lalu kembalikan intent pemasangannya.
     *
     * null bila assetnya tidak ada (build tanpa engine) atau gagal disalin.
     */
    fun installIntent(context: Context): Intent? {
        val out = File(context.cacheDir, ASSET_NAME)
        try {
            context.assets.open(ASSET_NAME).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            return null
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        }.getOrNull() ?: return null
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Engine mana yang dipakai, berurutan.
     *
     * Kalau engine neural sudah terpasang, itu yang dipakai lebih dulu —
     * suaranya jauh lebih bagus dan tetap offline. Engine bawaan sistem
     * selalu jadi cadangan, jadi engine neural yang rusak tidak membuat
     * Aru-chan bisu.
     *
     * Fungsi murni supaya bisa dites tanpa Android.
     */
    fun engineCandidates(neuralInstalled: Boolean): List<String?> =
        buildList<String?> {
            if (neuralInstalled) add(PACKAGE)
            add(null)
        }
}
