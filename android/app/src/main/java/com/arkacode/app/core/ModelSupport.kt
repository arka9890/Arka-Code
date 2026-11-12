package com.arkacode.app.core

import com.arkacode.app.net.ModelInfo

/**
 * Kelayakan model terhadap engine opencode yang dibundel.
 *
 * Engine (v2.x, ARM64) hanya mendukung model ber-API `aisdk`
 * (OpenAI-compatible). Model ber-`api.type = "native"` gagal dengan
 * `SessionRunnerModel.UnsupportedApiError`, biasanya setelah tunggu
 * ~20-30 dtk — tampak seperti "lama/hang" padahal sebenarnya error.
 *
 * Utilitas murni (tanpa Android) agar bisa diuji unit.
 */
object ModelSupport {

    /** `native` tak didukung; selain itu (mis. `aisdk`) didukung. null = anggap didukung. */
    fun isApiSupported(apiType: String?): Boolean = !apiType.equals("native", ignoreCase = true)

    /** Model null (belum dipilih) dianggap belum tentu didukung, tapi layak dicoba. */
    fun isSupported(model: ModelInfo?): Boolean = model == null || isApiSupported(model.apiType)

    /** Saring model yang tak akan bisa dijalankan engine. */
    fun visibleModels(models: List<ModelInfo>): List<ModelInfo> = models.filter { isSupported(it) }

    /** Jumlah model yang disembunyikan karena tak didukung. */
    fun unsupportedCount(models: List<ModelInfo>): Int = models.count { !isSupported(it) }

    /**
     * Pilih model default yang aman: prioritaskan gratis + aktif + didukung,
     * lalu gratis + didukung, lalu apa pun yang didukung. null bila tak ada.
     */
    fun pickDefault(models: List<ModelInfo>): ModelInfo? {
        val ok = models.filter { isSupported(it) }
        return ok.firstOrNull { it.free && it.status.equals("active", ignoreCase = true) }
            ?: ok.firstOrNull { it.free }
            ?: ok.firstOrNull()
    }

    /**
     * Apakah sesi (dengan provider/model tersimpan) perlu dialihkan ke default?
     * Ya bila model belum ditetapkan, atau model itu ada di katalog tetapi tak
     * didukung. Model di luar katalog (mis. provider kustom) tak diutak-atik.
     */
    fun needsDefault(providerID: String?, modelID: String?, models: List<ModelInfo>): Boolean {
        if (modelID.isNullOrBlank() || providerID.isNullOrBlank()) return true
        val found = models.firstOrNull { it.id == modelID && it.providerID == providerID }
        return found != null && !isSupported(found)
    }
}
