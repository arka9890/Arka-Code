package com.arkacode.app.ui

/**
 * String khusus Arka Buddy — sengaja di file terpisah dari `I18n.kt` agar tidak
 * menyentuh berkas yang dipegang user. Dibaca dari Composable sehingga ikut
 * berganti saat `I18n.lang` berubah.
 */
object BuddyText {
    val title: String get() = "Arka Buddy"
    val nav: String get() = "Buddy"

    /** Baris status di bawah judul. */
    fun status(mood: BuddyMood): String = when (I18n.lang) {
        AppLang.ID -> when (mood) {
            BuddyMood.IDLE -> "Siap membantu — tahan tombol untuk bicara."
            BuddyMood.LISTENING -> "Mendengarkan…"
            BuddyMood.THOINKING -> "Berpikir…"
            BuddyMood.TALKING -> "Sedang bicara."
            BuddyMood.HAPPY -> "Senang bisa membantu!"
        }

        AppLang.EN -> when (mood) {
            BuddyMood.IDLE -> "Ready — hold the button to talk."
            BuddyMood.LISTENING -> "Listening…"
            BuddyMood.THOINKING -> "Thinking…"
            BuddyMood.TALKING -> "Talking."
            BuddyMood.HAPPY -> "Happy to help!"
        }
    }

    /** Label tombol pemilih keadaan (pratinjau wajah). */
    fun moodLabel(mood: BuddyMood): String = when (I18n.lang) {
        AppLang.ID -> when (mood) {
            BuddyMood.IDLE -> "Siap"
            BuddyMood.LISTENING -> "Mendengar"
            BuddyMood.THOINKING -> "Berpikir"
            BuddyMood.TALKING -> "Bicara"
            BuddyMood.HAPPY -> "Senang"
        }

        AppLang.EN -> when (mood) {
            BuddyMood.IDLE -> "Idle"
            BuddyMood.LISTENING -> "Listening"
            BuddyMood.THOINKING -> "Thinking"
            BuddyMood.TALKING -> "Talking"
            BuddyMood.HAPPY -> "Happy"
        }
    }
}
