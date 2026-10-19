package com.arkacode.app.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * Siapkan installer engine lalu kembalikan intent pemasangannya.
     *
     * Hasil dikembalikan sebagai [Result] supaya penyebab kegagalan (storage
     * penuh, URI tidak bisa dibuat, ...) bisa ditampilkan ke pengguna —
     * bukan hilang diam-diam.
     *
     * Penyalinan 148 MB dilakukan di thread IO; berkas hasilnya disimpan di
     * storage internal aplikasi, bukan cache, supaya tidaksegera dibersihkan
     * sistem saat cache dibersihkan.
     */
    suspend fun prepareInstall(context: Context): Result<Intent> = withContext(Dispatchers.IO) {
        runCatching {
            val out = File(context.filesDir, ASSET_NAME)
            val expected = context.assets.open(ASSET_NAME).use { it.available() }
            // Berkas yang sudah ada & ukurannya cocok → pakai lagi, jangan
            // salin 148 MB berulang.
            if (out.length() != expected.toLong()) {
                out.outputStream().use { output ->
                    context.assets.open(ASSET_NAME).use { input -> input.copyTo(output) }
                }
            }
            if (out.length() == 0L) error("berkas installer kosong")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                out,
            )
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Beberapa versi Android hanya menerapkan grant URI kalau ada
                // ClipData; tanpa ini installer sistem tak bisa membaca berkas.
                .also { it.clipData = ClipData.newRawUri("", uri) }
        }
    }

    /**
     * Boleh memasang aplikasi lain?
     *
     * Android 8+ memintanya secara eksplisit; tanpa itu, installer sistem
     * menolak danMUATAN pun tak terlihat.
     */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Layar "izinkan aplikasi dari sumber ini" untuk Arkacode. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        )

    /** Buang installer yang sudah tak dipakai (engine-nya sudah terpasang). */
    fun cleanup(context: Context) {
        if (!isInstalled(context)) return
        runCatching { File(context.filesDir, ASSET_NAME).delete() }
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
