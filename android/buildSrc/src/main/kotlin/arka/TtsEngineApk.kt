package arka

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Menyiapkan installer TTS engine sherpa-onnx (Bahasa Indonesia) ke folder assets.
 *
 * Model neural-nya (Supertonic 3, 10 suara: F1-F5 cewe + M1-M5 cowok) ikut
 * dibundel di dalam APK engine, jadi satu file ini sudah cukup — pengguna tak
 * perlu mengunduh model terpisah.
 *
 * Hanya ABI arm64-v8a yang dibundel: APK engine per ABI besar (~150 MB),
 * sementara sebagian besar perangkat Android sekarang arm64. Perangkat lain
 * tetap jalan dengan TTS bawaan sistem — Aru-chan tetap bicara Indonesia,
 * hanya suaranya bukan neural.
 *
 * Kalau unduhan gagal (mis. offline), build TETAP jalan: APK dibuat tanpa
 * installer engine dan Aru-chan memakai TTS sistem. Lewati total dengan:
 * ./gradlew assembleDebug -Ptts.skip=true
 */
object TtsEngineApk {

    /** Versi engine sherpa-onnx yang dibundel. */
    const val ENGINE_VERSION = "1.13.8"

    /** Nama paket engine — dipakai aplikasi untuk knowing & memasang engine. */
    const val PACKAGE = "com.k2fsa.sherpa.onnx.tts.engine"

    /** ABI yang dibundel. */
    const val ABI = "arm64-v8a"

    /** Nama berkas unduhan (memuat versi + model, jadi unik). */
    const val FILE_NAME =
        "sherpa-onnx-$ENGINE_VERSION-$ABI-ind-tts-engine-sherpa-onnx-supertonic-3-tts-int8-2026-05-11.apk"

    private const val BASE_URL =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/$ENGINE_VERSION"

    const val URL = "$BASE_URL/$FILE_NAME"

    /**
     * Nama berkas di dalam assets. Sengaja nama pendek & stabil supaya
     * aplikasi bisa membacanya tanpa tahu nama unduhan.
     */
    const val ASSET_NAME = "tts-engine.apk"

    /**
     * Ukuran minimum yang dianggap sah. File aslinya ~148 MB; unduhan yang
     * gagal biasanya berupa halaman HTML kecil — lebih baik dianggap gagal
     * daripada dibundel ke APK.
     */
    const val MIN_BYTES = 100L * 1024 * 1024

    /** Penanda versi, supaya bump versi engine memaksa unduhan ulang. */
    const val VERSION = "$ENGINE_VERSION-$ABI"

    /**
     * @return true kalau installer engine siap di folder assets.
     */
    fun prepare(outputDir: File, cacheDir: File, log: (String) -> Unit): Boolean {
        outputDir.mkdirs()
        cacheDir.mkdirs()

        val target = File(outputDir, ASSET_NAME)
        if (target.exists() && target.length() >= MIN_BYTES) {
            log("[tts] installer engine sudah ada (cache) — dilewati")
            return true
        }

        val cached = File(cacheDir, FILE_NAME)
        if (!cached.exists() || cached.length() < MIN_BYTES) {
            if (!download(URL, cached, log)) {
                log("[tts] gagal mengunduh installer engine dari $URL")
                return false
            }
        }
        if (cached.length() < MIN_BYTES) {
            log("[tts] berkas unduhan terlalu kecil (${cached.length()} byte) — dianggap gagal")
            return false
        }

        cached.copyTo(target, overwrite = true)
        log("[tts] installer engine siap (${target.length()} byte) → ${target.absolutePath}")
        return true
    }

    private fun download(url: String, target: File, log: (String) -> Unit): Boolean {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 600_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "arka-gradle")
            try {
                if (conn.responseCode !in 200..299) {
                    log("[tts] HTTP ${conn.responseCode} untuk $url")
                    return false
                }
                conn.inputStream.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            log("[tts] gagal unduh $url (${e.message})")
            false
        }
    }
}
