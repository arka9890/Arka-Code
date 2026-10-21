package com.arkacode.app.ui

/**
 * String untuk tombol unggah berkas di panel Files. File terpisah dari
 * `I18n.kt` agar tidak menyentuh berkas yang dipegang user (lihat BuddyText).
 */
object UploadText {

    /** Deskripsi ikon tombol. */
    val pick: String get() = when (I18n.lang) {
        AppLang.ID -> "Unggah berkas ke workspace"
        AppLang.EN -> "Upload file to workspace"
    }

    fun progress(name: String): String = when (I18n.lang) {
        AppLang.ID -> "Mengunggah: $name"
        AppLang.EN -> "Uploading: $name"
    }

    fun done(count: Int, name: String): String = when (I18n.lang) {
        AppLang.ID -> if (count == 1) "$name tersimpan" else "$name diekstrak ($count berkas)"
        AppLang.EN -> if (count == 1) "$name saved" else "$name extracted ($count files)"
    }

    fun failed(name: String, why: String): String = when (I18n.lang) {
        AppLang.ID -> "Gagal unggah $name: $why"
        AppLang.EN -> "Failed to upload $name: $why"
    }
}

/** String untuk tombol ekspor ZIP di panel Files. */
object ExportText {

    /** Deskripsi ikon tombol. */
    val pick: String get() = when (I18n.lang) {
        AppLang.ID -> "Bundel folder ini jadi ZIP"
        AppLang.EN -> "Bundle this folder as ZIP"
    }

    fun zipName(cwd: String): String =
        (cwd.substringAfterLast('/').ifBlank { "workspace" }) + ".zip"

    fun done(count: Int): String = when (I18n.lang) {
        AppLang.ID -> if (count == 1) "ZIP dibuat (1 berkas)" else "ZIP dibuat ($count berkas)"
        AppLang.EN -> if (count == 1) "ZIP created (1 file)" else "ZIP created ($count files)"
    }

    fun failed(why: String): String = when (I18n.lang) {
        AppLang.ID -> "Gagal membuat ZIP: $why"
        AppLang.EN -> "Failed to create ZIP: $why"
    }
}
