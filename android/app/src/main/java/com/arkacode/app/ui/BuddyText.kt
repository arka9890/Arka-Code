package com.arkacode.app.ui

/**
 * String khusus Aru-chan — sengaja di file terpisah dari `I18n.kt` agar tidak
 * menyentuh berkas yang dipegang user. Dibaca dari Composable sehingga ikut
 * berganti saat `I18n.lang` berubah.
 */
object BuddyText {
    val title: String get() = "Aru-chan"
    val nav: String get() = "Aru-chan"

    // --- Suara ----------------------------------------------------------

    val sayHint: String get() = when (I18n.lang) {
        AppLang.ID -> "Ketik kalimat untuk didengarkan"
        AppLang.EN -> "Type a sentence to hear it"
    }

    val speak: String get() = when (I18n.lang) {
        AppLang.ID -> "Dengarkan"
        AppLang.EN -> "Speak"
    }

    // --- Suara neural (TTS engine sherpa-onnx) ---------------------------

    val installNeuralVoice: String get() = when (I18n.lang) {
        AppLang.ID -> "Pasang suara neural"
        AppLang.EN -> "Install neural voice"
    }

    val preparingVoice: String get() = when (I18n.lang) {
        AppLang.ID -> "Menyiapkan installer…"
        AppLang.EN -> "Preparing installer…"
    }

    fun installFailed(why: String): String = when (I18n.lang) {
        AppLang.ID -> "Gagal menyiapkan suara neural: $why"
        AppLang.EN -> "Could not prepare the neural voice: $why"
    }

    val installPermissionDenied: String get() = when (I18n.lang) {
        AppLang.ID -> "Arkacode belum diizinkan memasang aplikasi. " +
            "Izinkan di Setelan Android › Keamanan › Pasang aplikasi tidak dikenal."
        AppLang.EN -> "Arkacode is not allowed to install apps. " +
            "Allow it in Android Settings › Security › Install unknown apps."
    }

    val neuralVoiceHint: String get() = when (I18n.lang) {
        AppLang.ID -> "Engine suara Indonesia ikut dalam aplikasi ini (~150 MB). " +
            "Setelah dipasang, pilih \"SherpaOnnxTtsEngine\" di Setelan Android › " +
            "Bahasa & masukan › Output teks-ke-speech."
        AppLang.EN -> "The Indonesian voice engine ships inside this app (~150 MB). " +
            "After installing, pick \"SherpaOnnxTtsEngine\" in Android Settings › " +
            "Languages & input › Text-to-speech output."
    }

    fun failed(why: String): String = when (I18n.lang) {
        AppLang.ID -> "Suara gagal: $why"
        AppLang.EN -> "Voice failed: $why"
    }

    /** Galat saat engine tidak bisa menjawab (bukan galat suara). */
    fun askFailed(why: String): String = when (I18n.lang) {
        AppLang.ID -> "Buddy tidak bisa menjawab: $why"
        AppLang.EN -> "Buddy could not answer: $why"
    }

    // --- Tahap 3: tahan-untuk-bicara -------------------------------------

    val holdHint: String get() = when (I18n.lang) {
        AppLang.ID -> "Tahan untuk bicara"
        AppLang.EN -> "Hold to talk"
    }

    val micDenied: String get() = when (I18n.lang) {
        AppLang.ID -> "Izin mikrofon diperlukan supaya Buddy bisa mendengar."
        AppLang.EN -> "Microphone permission is needed so Buddy can listen."
    }

    val heard: String get() = when (I18n.lang) {
        AppLang.ID -> "Terdengar:"
        AppLang.EN -> "Heard:"
    }

    val nothingHeard: String get() = when (I18n.lang) {
        AppLang.ID -> "Tidak ada yang terdengar. Coba lagi."
        AppLang.EN -> "Nothing was heard. Try again."
    }

    /** Baris status di bawah judul. */
    fun status(mood: BuddyMood): String = when (I18n.lang) {
        AppLang.ID -> when (mood) {
            BuddyMood.IDLE -> "Siap membantu — tahan tombol untuk bicara."
            BuddyMood.LISTENING -> "Mendengarkan…"
            BuddyMood.THINKING -> "Berpikir…"
            BuddyMood.TALKING -> "Sedang bicara."
            BuddyMood.HAPPY -> "Senang bisa membantu!"
        }

        AppLang.EN -> when (mood) {
            BuddyMood.IDLE -> "Ready — hold the button to talk."
            BuddyMood.LISTENING -> "Listening…"
            BuddyMood.THINKING -> "Thinking…"
            BuddyMood.TALKING -> "Talking."
            BuddyMood.HAPPY -> "Happy to help!"
        }
    }

    /** Label tombol pemilih keadaan (pratinjau wajah). */
    fun moodLabel(mood: BuddyMood): String = when (I18n.lang) {
        AppLang.ID -> when (mood) {
            BuddyMood.IDLE -> "Siap"
            BuddyMood.LISTENING -> "Mendengar"
            BuddyMood.THINKING -> "Berpikir"
            BuddyMood.TALKING -> "Bicara"
            BuddyMood.HAPPY -> "Senang"
        }

        AppLang.EN -> when (mood) {
            BuddyMood.IDLE -> "Idle"
            BuddyMood.LISTENING -> "Listening"
            BuddyMood.THINKING -> "Thinking"
            BuddyMood.TALKING -> "Talking"
            BuddyMood.HAPPY -> "Happy"
        }
    }
}
