package com.arkacode.app.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Pembungkus `SpeechRecognizer` bawaan Android untuk Aru-chan.
 *
 * Dipilih karena: gratis, on-device (di beberapa perangkat), tanpa API key,
 * dan tanpa dependensi. Syaratnya izin RECORD_AUDIO dan ada penyedia layanan
 * pengenalan suara terpasang (biasanya aplikasi Google).
 *
 * Semua callback dipanggil di main thread, sesuai kontrak SpeechRecognizer.
 *
 * Tahan-untuk-bicara: recognizer BERHENTI SENDIRI begitu mendeteksi jeda,
 * tidak peduli tombol masih ditekan. Karena itu selama tombol ditahan
 * ([holding]), sesi yang selesai sendiri dimulai lagi dan hasilnya ditumpuk di
 * [buffer]; ucapannya baru dikirim ketika tombol dilepas. Tanpa ini pengguna
 * berhenti terekam pada jeda pertama.
 */
object BuddySpeech {

    /**
     * Galat yang berarti "belum ada suara yang cocok", bukan kegagalan.
     *
     * Keduanya hanya muncul setelah recognizer menjalankan waktunya sendiri,
     * jadi mengulanginya tidak akan memutar galat berkecepatan tinggi.
     * Galat lain (izin, audio, jaringan) tidak akan membaik dengan mengulang.
     */
    private val RETRYABLE = setOf(
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_NO_MATCH,
    )

    /**
     * Jeda sebelum recognizer menyimpulkan selesai. Hanya petunjuk —
     * penerapannya tergantung penyedia layanan, makanya jalur mulai-ulang saat
     * hasil akhir tiba sambil tombol masih ditahan tetap diperlukan. Tombol
     * dilepas memaksa selesai lewat [stopListening], jadi nilai ini tidak
     * menunda pengguna.
     */
    private const val COMPLETE_SILENCE_MS = 4000L

    private var recognizer: SpeechRecognizer? = null

    /** Aplikasi context, supaya object ini tidak menahan Activity. */
    private var app: Context? = null

    /** Tombol masih ditekan. Selama true, sesi yang selesai sendiri diulang. */
    private var holding = false

    /** Ucapan yang terkumpul selama satu kali menahan tombol. */
    private var buffer = ""

    private var onText: ((String?) -> Unit)? = null
    private var onFailed: ((Int) -> Unit)? = null

    /** Cek dulu sebelum memulai; false berarti tidak ada penyedia STT. */
    fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * Mulai mendengarkan. [onText] dipanggil sekali ketika tombol dilepas;
     * null kalau tidak ada suara sama sekali. [onFailed] dipanggil sekali
     * bila berakhir karena galat.
     */
    fun start(context: Context, onText: (String?) -> Unit, onFailed: (Int) -> Unit) {
        destroy()
        if (!isAvailable(context)) {
            onFailed(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        app = context.applicationContext
        this.onText = onText
        this.onFailed = onFailed
        holding = true
        buffer = ""
        listen()
    }

    /**
     * Selesai mendengarkan ketika tombol dilepas.
     *
     * Sengaja memakai [SpeechRecognizer.stopListening] dan bukan `cancel()`:
     * `cancel()` membatalkan sesi tanpa pernah memanggil `onResults`, jadi
     * seluruh hasil yang sudah tertumpuk terbuang.
     */
    fun stopListening() {
        holding = false
        val r = recognizer
        // Tidak ada sesi aktif (mis. baru selesai): kirim apa yang sudah ada
        // supaya hasilnya tidak hilang begitu saja.
        if (r == null) {
            finish(buffer.ifEmpty { null })
            return
        }
        runCatching { r.stopListening() }
    }

    /** Hentikan paksa dan lepaskan semuanya. Untuk saat layar ditutup. */
    fun destroy() {
        releaseCurrent()
        onText = null
        onFailed = null
        holding = false
        buffer = ""
        app = null
    }

    // ------------------------------------------------------------- internal

    private fun releaseCurrent() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun listen() {
        val context = app ?: return finish(null)
        releaseCurrent()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (recognizer !== r) return
                collect(results)
                when (speechAction(held = holding, error = null, retryable = RETRYABLE, said = buffer)) {
                    SpeechAction.RETRY -> restart()
                    else -> finish(buffer.ifEmpty { null })
                }
            }

            override fun onError(error: Int) {
                if (recognizer !== r) return
                when (speechAction(held = holding, error = error, retryable = RETRYABLE, said = buffer)) {
                    SpeechAction.RETRY -> restart()
                    SpeechAction.DELIVER -> finish(buffer.ifEmpty { null })
                    SpeechAction.FAIL -> finish(null, error)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(data: ByteArray?) = Unit
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
            // Tambah jeda yang diperbolehkan supaya berpikir sejenak tidak
            // memutus ucapannya.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MS,
            )
        }
        r.startListening(intent)
    }

    /**
     * Ganti sesi recognizer. Hanya dipanggil dari callback sesi yang aktif,
     * yang sudah dipastikan lewat pemeriksaan `recognizer === r`.
     */
    private fun restart() {
        releaseCurrent()
        listen()
    }

    private fun collect(results: Bundle?) {
        val chunk = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        buffer = joinSpeech(buffer, chunk)
    }

    /** Tutup sesi lalu kirim hasilnya tepat satu kali. */
    private fun finish(text: String?, error: Int? = null) {
        val cbText = onText
        val cbFailed = onFailed
        onText = null
        onFailed = null
        holding = false
        buffer = ""
        app = null
        releaseCurrent()
        if (error != null) cbFailed?.invoke(error) else cbText?.invoke(text)
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

/** Nasib satu kejadian recognizer selama menahan tombol. */
enum class SpeechAction {
    /** Mulai sesi baru; ucapan yang sudah terkumpul tetap dipertahankan. */
    RETRY,

    /** Kirim apa yang sudah terkumpul, termasuk bila kosong. */
    DELIVER,

    /** Berakhir dengan galat yang perlu ditampilkan. */
    FAIL,
}

/**
 * Putuskan nasib sesi recognizer.
 *
 * Aturan utamanya: selama tombol ditahan, kejadian yang berarti "belum ada
 * suara yang cocok" — termasuk hasil akhir yang dikirim recognizer sendiri
 * karena mendeteksi jeda — tidak boleh mengakhiri perekaman. Ucapan yang sudah
 * terkumpul juga tidak boleh dibuang oleh kegagalan sesi terakhir.
 *
 * Fungsi murni tanpa Android supaya bisa dites: keputusan ini menentukan
 * apakah ucapan pengguna terpakai atau hilang.
 *
 * @param held tombol masih ditekan
 * @param error kode galat, atau null bila kejadiannya hasil akhir (bukan galat)
 * @param retryable galat yang hanya berarti "belum ada suara yang cocok"
 * @param said ucapan yang sudah terkumpul sejauh ini
 */
fun speechAction(
    held: Boolean,
    error: Int?,
    retryable: Set<Int>,
    said: String,
): SpeechAction = when {
    held && (error == null || error in retryable) -> SpeechAction.RETRY
    said.isNotEmpty() -> SpeechAction.DELIVER
    error != null -> SpeechAction.FAIL
    else -> SpeechAction.DELIVER
}

/** Tampung satu potongan hasil recognizer ke ucapan yang sudah ada. */
fun joinSpeech(buffer: String, chunk: String): String {
    val clean = chunk.trim()
    return when {
        clean.isEmpty() -> buffer
        buffer.isEmpty() -> clean
        else -> "$buffer $clean"
    }
}
