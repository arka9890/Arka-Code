package com.arkacode.app.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sintesis suara Arka Buddy memakai Gemini TTS (gratis di free tier).
 *
 * Kenapa Gemini: `gemini-2.5-flash-preview-tts` ada di free tier, bisa
 * diarahkan gayanya, dan tidak butuh kartu kredit. `gemini-3.8-flash-tts`
 * sengaja tidak dipakai karena TIDAK punya free tier.
 *
 * Responsnya PCM mentah (16-bit, 24 kHz, mono) sehingga langsung diputar lewat
 * AudioTrack — tidak ada codec, tidak ada dependensi baru.
 */
object BuddyVoice {

    private const val MODEL = "gemini-2.5-flash-preview-tts"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    /** Nama penyimpanan API key di SecureStore. */
    const val KEY_NAME = "gemini_tts"

    /** Suara bawaan. */
    private const val VOICE = "Kore"

    /**
     * Instruksi gaya yang mendahului teks pengguna.
     *
     * Wajib untuk model generasi 2.5: di sana arahan gaya ditulis di dalam
     * teks (bukan lewat `speech_metadata`, itu khas 3.8). Tanpa ini model
     * menganggap masukan sebagai percakapan lalu mencoba *menjawab*nya — dan
     * karena hanya boleh mengeluarkan audio, permintaannya ditolak 400 dengan
     * pesan "Model tried to generate text, but it should only be audio".
     */
    private const val STYLE_PREFIX = "Say in a warm, friendly voice: "

    const val SAMPLE_RATE = 24_000

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    fun hasKey(context: Context): Boolean =
        !SecureStore.getAuth(context, KEY_NAME).isNullOrBlank()

    fun saveKey(context: Context, key: String) = SecureStore.setAuth(context, KEY_NAME, key)

    /** Teks dipotong supaya tidak melampaui batas permintaan TTS. */
    fun fitText(raw: String, max: Int = 400): String =
        raw.trim().take(max).trimEnd()

    /**
     * Ubah teks menjadi audio PCM.
     *
     * @throws IOException bila jaringan, kunci, atau respons tidak beres —
     *   pesannya sudah layak ditampilkan ke pengguna.
     */
    suspend fun synthesize(context: Context, text: String): ByteArray =
        withContext(Dispatchers.IO) {
            val key = SecureStore.getAuth(context, KEY_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: throw IOException("API key Gemini belum diisi")

            val clean = fitText(text)
            if (clean.isEmpty()) throw IOException("tidak ada teks untuk dibacakan")
            val speakable = STYLE_PREFIX + clean

            val payload = buildJsonObject {
                put(
                    "contents",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(buildJsonObject { put("text", speakable) })
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "generationConfig",
                    buildJsonObject {
                        put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                        put(
                            "speechConfig",
                            buildJsonObject {
                                put(
                                    "voiceConfig",
                                    buildJsonObject {
                                        put(
                                            "prebuiltVoiceConfig",
                                            buildJsonObject { put("voiceName", VOICE) },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }.toString()

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("x-goog-api-key", key)
                .post(payload.toRequestBody(jsonType))
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Gemini menolak (${response.code}): ${errorFrom(body)}")
                }
                decodeAudio(body)
            }
        }

    /** Ambil berkas audio base64 dari respons. */
    fun decodeAudio(body: String): ByteArray {
        val data = runCatching {
            json.parseToJsonElement(body).jsonObject
                .let { it["candidates"]?.jsonArray?.get(0)?.jsonObject }
                ?.let { it["content"]?.jsonObject }
                ?.let { it["parts"]?.jsonArray?.get(0)?.jsonObject }
                ?.let { it["inlineData"]?.jsonObject ?: it["inline_data"]?.jsonObject }
                ?.let { it["data"]?.jsonPrimitive?.content }
        }.getOrNull().orEmpty()

        if (data.isBlank()) throw IOException("respons Gemini tidak berisi audio")
        return Base64.decode(data, Base64.NO_WRAP)
    }

    fun errorFrom(body: String): String =
        runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull().orEmpty().ifBlank { "kunci atau model salah" }

    /**
     * Putar PCM 16-bit 24 kHz mono. Blocking sampai suara benar-benar habis,
     * supaya pemanggil bisa mengembalikan wajah ke IDLE setelah fungsi ini kembali.
     */
    suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext

        val channel = AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, channel, format)
        if (minBuffer <= 0) throw IOException("perangkat tidak mendukung pemutaran audio")

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(format)
                    .setChannelMask(channel)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, minOf(pcm.size - offset, minBuffer))
                if (written <= 0) break
                offset += written
            }
            // MODE_STREAM: stop() kembali setelah buffer terakhir selesai diputar.
            track.stop()
        } finally {
            track.release()
        }
    }
}
