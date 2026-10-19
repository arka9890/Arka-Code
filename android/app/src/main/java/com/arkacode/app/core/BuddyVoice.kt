package com.arkacode.app.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suara Aru-chan memakai TTS bawaan Android (`android.speech.tts`).
 *
 * Kenapa lokal: gratis, offline, tanpa API key, dan tidak ada satu huruf pun
 * yang dikirim ke server manapun.
 *
 * Bahasa **dipaksa Indonesia**, bukan mengikuti locale perangkat — HP bisa
 * berbahasa Inggris sementara Aru-chan tetap bicara Indonesia. Suara terbaik
 * yang tersedia untuk bahasa itu yang dipakai: `id_ID` bila ada, lalu `id`,
 * lalu `ind` (engine sherpa-onnx membandingkan kode ISO 639-3 apa adanya).
 *
 * Sebelumnya memakai Gemini TTS (`gemini-2.5-flash-preview-tts`) yang butuh API
 * key dan jaringan; diganti lokal agar tidak bergantung kunci & daring.
 *
 * Engine diinisialisasi sekali (proses async) lalu dipakai ulang; [destroy]
 * melepasnya saat layar Buddy ditutup.
 */
object BuddyVoice {

    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    /**
     * Kode bahasa Indonesia yang dicoba, urut dari paling pas.
     *
     * `id_ID` dan `id` dipakai engine berbasis ISO 639-1 (Google, Samsung),
     * `ind` dipakai engine yang membandingkan kode ISO 639-3 apa adanya
     * (sherpa-onnx).
     */
    private val ID_LOCALES = listOf(Locale("id", "ID"), Locale("id"), Locale("ind"))

    /** Sedikit lebih lambat dan sedikit lebih tinggi — suara "imut". */
    private const val SPEECH_RATE = 0.98f
    private const val SPEECH_PITCH = 1.05f

    /** Teks dibersihkan & dipotong supaya satu ucapan tidak terlalu panjang. */
    fun fitText(raw: String, max: Int = 500): String = raw.trim().take(max).trimEnd()

    /** Siapkan engine bila belum ada (idempoten) lalu tunggu sampai siap. */
    private suspend fun engineFor(context: Context): TextToSpeech {
        engine?.let { existing ->
            if (ready?.await() == true) return existing
            destroy() // engine lama gagal siap — buang lalu coba ulang sekali
        }

        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        val created = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                deferred.complete(true)
            } else {
                deferred.complete(false)
            }
        }
        engine = created

        if (deferred.await()) {
            // Disiapkan di sini, bukan di dalam callback: callback bisa jalan
            // sebelum `engine` terisi, jadi `engine?.` di dalamnya bisa null
            // dan bahasa tak pernah dipasang.
            prepare(created)
            return created
        }
        destroy()
        throw IOException("TTS tidak tersedia di perangkat ini")
    }

    /**
     * Pasang bahasa Indonesia, suara terbaiknya, serta kecepatan & tinggi
     * suara.
     *
     * Bila tak satu pun kode bahasa Indonesia diterima engine, bahasa
     * defaultnya dibiarkan — lebih baik daripada diam.
     */
    private fun prepare(tts: TextToSpeech) {
        for (locale in ID_LOCALES) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                break
            }
        }
        val voices = tts.voices
        pickVoice(voices.map { it.toChoice() })?.let { picked ->
            voices.firstOrNull { it.name == picked.name }?.let { tts.voice = it }
        }
        tts.setSpeechRate(SPEECH_RATE)
        tts.setPitch(SPEECH_PITCH)
    }

    /**
     * Bacakan [text] blocking sampai ucapan benar-benar selesai, supaya
     * pemanggil bisa mengembalikan wajah ke IDLE/HAPPY setelah fungsi ini
     * kembali.
     *
     * @throws IOException bila TTS tidak tersedia atau gagal membacakan —
     *   pesannya sudah layak ditampilkan ke pengguna.
     */
    suspend fun speak(context: Context, text: String) {
        val clean = fitText(text)
        if (clean.isEmpty()) return
        val tts = engineFor(context)

        suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resumeWithException(IOException("TTS berhenti karena galat"))
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (cont.isActive) cont.resumeWithException(IOException("TTS berhenti karena galat"))
                }
            })
            // Bila pemanggil batal (mis. layar ditutup di tengah ucapan), hentikan
            // audio supaya tidak nyangkut di latar belakang.
            cont.invokeOnCancellation { tts.stop() }

            val queued = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            if (queued != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resumeWithException(IOException("TTS tidak bisa membacakan teks"))
            }
        }
    }

    /** Lepas engine. Dipanggil saat layar Buddy ditutup. */
    fun destroy() {
        engine?.shutdown()
        engine = null
        ready = null
    }
}

/**
 * Satu voice TTS, bentuk sederhana supaya bisa diuji tanpa Android.
 *
 * Mirip `android.speech.tts.Voice`, tapi field-nya diambil nilai biasa
 * supaya logika pemilihan bisa dites di JVM.
 */
data class VoiceChoice(
    val name: String,
    val language: String,
    val region: String,
    val quality: Int,
    val needsNetwork: Boolean,
)

/**
 * Kode bahasa Indonesia: ISO 639-1 (`id`) dan ISO 639-3 (`ind`) menunjuk
 * bahasa yang sama, tapi engine berbeda membandingkan kode yang berbeda —
 * sherpa-onnx mencocokkan `ind` apa adanya.
 */
private val ID_CODES = setOf("id", "ind")

/**
 * Pilih suara terbaik untuk bahasa Indonesia.
 *
 * Urutannya: wilayah persis (`ID`) lebih dulu, lalu yang **tidak butuh
 * jaringan** (Aru-chan harus tetap bicara tanpa internet), lalu kualitas
 * tertinggi, lalu nama supaya hasilnya selalu sama.
 *
 * Fungsi murni supaya bisa dites tanpa Android.
 */
fun pickVoice(voices: List<VoiceChoice>, region: String = "ID"): VoiceChoice? =
    voices
        .filter { it.language.lowercase() in ID_CODES }
        .sortedWith(
            compareByDescending<VoiceChoice> { it.region.equals(region, ignoreCase = true) }
                .thenBy { it.needsNetwork }
                .thenByDescending { it.quality }
                .thenBy { it.name }
        )
        .firstOrNull()

/** Ubah voice Android jadi bentuk yang bisa diuji. */
private fun android.speech.tts.Voice.toChoice() = VoiceChoice(
    name = name,
    language = locale.language,
    region = locale.country,
    quality = quality,
    needsNetwork = isNetworkConnectionRequired,
)
