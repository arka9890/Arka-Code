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
 * Suara Arka Buddy memakai TTS bawaan Android (`android.speech.tts`).
 *
 * Kenapa lokal: gratis, offline, tanpa API key, dan tidak ada satu huruf pun
 * yang dikirim ke server manapun. Bahasa mengikuti locale perangkat — bila HP
 * berbahasa Indonesia, Buddy otomatis bicara Indonesia. Suara mengikuti TTS
 * engine yang terpasang (Google TTS / bawaan pabrikan).
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
                // Bahasa mengikuti perangkat. Bila locale tak tersedia di engine
                // ini tetap jalan dengan bahasa defaultnya (nilai kembalian
                // diabaikan: LTTS_LANG_NOT_SUPPORTED bukan galat mematikan).
                engine?.setLanguage(Locale.getDefault())
                deferred.complete(true)
            } else {
                deferred.complete(false)
            }
        }
        engine = created

        if (deferred.await()) return created
        destroy()
        throw IOException("TTS tidak tersedia di perangkat ini")
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
