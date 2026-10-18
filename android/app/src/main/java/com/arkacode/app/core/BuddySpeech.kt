package com.arkacode.app.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Pembungkus `SpeechRecognizer` bawaan Android untuk Arka Buddy.
 *
 * Dipilih karena: gratis, on-device (di beberapa perangkat), tanpa API key,
 * dan tanpa dependensi. Syaratnya izin RECORD_AUDIO dan ada penyedia layanan
 * pengenalan suara terpasang (biasanya aplikasi Google).
 *
 * Semua callback dipanggil di main thread, sesuai kontrak SpeechRecognizer.
 */
object BuddySpeech {

    private var recognizer: SpeechRecognizer? = null

    /** Cek dulu sebelum memulai; false berarti tidak ada penyedia STT. */
    fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * Mulai mendengarkan sampai [stopListening] dipanggil atau pengguna
     * berhenti bicara sendiri.
     *
     * @param onText dipanggil sekali dengan hasil akhir; null kalau kosong.
     * @param onFailed dipanggil sekali dengan kode galat SpeechRecognizer.
     */
    fun start(context: Context, onText: (String?) -> Unit, onFailed: (Int) -> Unit) {
        destroy()
        if (!isAvailable(context)) {
            onFailed(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                recognizer = null
                r.destroy()
                onText(text?.takeIf { it.isNotEmpty() })
            }

            override fun onError(error: Int) {
                recognizer = null
                r.destroy()
                onFailed(error)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // Ikuti bahasa perangkat, jadi otomatis Indonesia kalau HP-nya Indonesia.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, context.resources.configuration.locales[0])
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    /**
     * Selesai mendengarkan dan tunggu hasil terakhir.
     *
     * Sengaja memakai [SpeechRecognizer.stopListening] dan bukan `cancel()`:
     * `cancel()` membatalkan sesi dan tidak pernah memanggil `onResults`,
     * sehingga hasilnya tidak akan pernah sampai. `stopListening()` justru
     * meminta recognizer menutup rekaman lalu mengirim hasilnya.
     *
     * Recognizer sengaja tidak dihancurkan di sini — listener-nya harus tetap
     * hidup sampai `onResults`/`onError` tiba.
     */
    fun stopListening() {
        recognizer?.let { runCatching { it.stopListening() } }
    }

    /** Hentikan paksa dan lepaskan recognizer. Untuk saat layar ditutup. */
    fun destroy() {
        recognizer?.let {
            recognizer = null
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
    }

    /** Pesan galat yang layak ditampilkan ke pengguna. */
    fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "izin mikrofon belum diberikan"
        SpeechRecognizer.ERROR_NO_MATCH -> "tidak ada suara yang terdengar"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "tidak ada suara sama sekali"
        SpeechRecognizer.ERROR_AUDIO -> "gangguan perekaman audio"
        SpeechRecognizer.ERROR_NETWORK -> "layanan pengenalan suara butuh jaringan"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "jaringan terlalu lambat"
        SpeechRecognizer.ERROR_SERVER -> "layanan pengenalan suara sedang bermasalah"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "layanan pengenalan suara sibuk"
        SpeechRecognizer.ERROR_CLIENT -> "pengenalan suara tidak tersedia di perangkat ini"
        else -> "pengenalan suara gagal ($code)"
    }
}
