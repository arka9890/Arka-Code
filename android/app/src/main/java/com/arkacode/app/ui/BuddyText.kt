package com.arkacode.app.ui

/**
 * String khusus Arka Buddy — sengaja di file terpisah dari `I18n.kt` agar tidak
 * menyentuh berkas yang dipegang user. Dibaca dari Composable sehingga ikut
 * berganti saat `I18n.lang` berubah.
 */
object BuddyText {
    val title: String get() = "Arka Buddy"
    val nav: String get() = "Buddy"

    // --- Tahap 2: suara -------------------------------------------------

    val keyHint: String get() = when (I18n.lang) {
        AppLang.ID -> "API key Gemini (Google AI Studio)"
        AppLang.EN -> "Gemini API key (Google AI Studio)"
    }

    val keySaved: String get() = when (I18n.lang) {
        AppLang.ID -> "Kunci tersimpan."
        AppLang.EN -> "Key saved."
    }

    val keyMissing: String get() = when (I18n.lang) {
        AppLang.ID -> "Isi API key Gemini dulu agar suara bisa nyala."
        AppLang.EN -> "Enter the Gemini API key first to enable voice."
    }

    val sayHint: String get() = when (I18n.lang) {
        AppLang.ID -> "Ketik kalimat untuk didengarkan"
        AppLang.EN -> "Type a sentence to hear it"
    }

    val speak: String get() = when (I18n.lang) {
        AppLang.ID -> "Dengarkan"
        AppLang.EN -> "Speak"
    }

    fun failed(why: String): String = when (I18n.lang) {
        AppLang.ID -> "Suara gagal: $why"
        AppLang.EN -> "Voice failed: $why"
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
