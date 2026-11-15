package com.arkacode.app.ui

/**
 * String untuk tombol unggah berkas di panel Files. File terpisah dari
 * `I18n.kt` agar tidak menyentuh berkas yang dipegang user.
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

/** String untuk tombol unduh satu berkas di panel Files. */
object DownloadText {

    /** Deskripsi ikon tombol. */
    val pick: String get() = when (I18n.lang) {
        AppLang.ID -> "Unduh berkas ini"
        AppLang.EN -> "Download this file"
    }

    fun done(name: String): String = when (I18n.lang) {
        AppLang.ID -> "$name tersimpan"
        AppLang.EN -> "$name saved"
    }

    fun failed(name: String, why: String): String = when (I18n.lang) {
        AppLang.ID -> "Gagal mengunduh $name: $why"
        AppLang.EN -> "Failed to download $name: $why"
    }
}

/** String untuk aksi "simpan langsung ke Download" (tanpa dialog SAF). */
object QuickSaveText {
    /** Deskripsi ikon tombol simpan langsung (file terbuka). */
    val pickFile: String get() = when (I18n.lang) {
        AppLang.ID -> "Simpan langsung ke folder Download"
        AppLang.EN -> "Save directly to the Download folder"
    }

    /** Deskripsi ikon tombol simpan langsung (folder/ZIP). */
    val pickZip: String get() = when (I18n.lang) {
        AppLang.ID -> "Bundel folder ini jadi ZIP ke Download"
        AppLang.EN -> "Bundle this folder as ZIP to Downloads"
    }

    fun doneFile(name: String): String = when (I18n.lang) {
        AppLang.ID -> "$name tersimpan ke folder Download"
        AppLang.EN -> "$name saved to the Download folder"
    }

    fun doneZip(count: Int): String = when (I18n.lang) {
        AppLang.ID ->
            if (count == 1) "ZIP tersimpan ke Download (1 berkas)"
            else "ZIP tersimpan ke Download ($count berkas)"
        AppLang.EN ->
            if (count == 1) "ZIP saved to Downloads (1 file)"
            else "ZIP saved to Downloads ($count files)"
    }

    fun failed(what: String, why: String): String = when (I18n.lang) {
        AppLang.ID -> "Gagal simpan ke Download $what: $why"
        AppLang.EN -> "Failed to save $what to Downloads: $why"
    }
}
