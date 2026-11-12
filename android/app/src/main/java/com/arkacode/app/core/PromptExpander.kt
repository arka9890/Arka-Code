package com.arkacode.app.core

/**
 * Perluas template command/skill menjadi teks prompt siap-kirim.
 *
 * Server `opencode` v2 tidak meng-expand slash command pada endpoint prompt
 * (`/api/session/:id/prompt`), jadi perluasan `$ARGUMENTS` / `$1..$n` dilakukan
 * di klien. Placeholder yang tidak dikenal dibiarkan apa adanya (aman).
 */
object PromptExpander {
    private const val ARGUMENTS = "\$ARGUMENTS"
    private const val POSITIONAL = "\$"

    fun expand(template: String, args: List<String>): String {
        if (template.isEmpty()) return template
        val all = args.joinToString(" ")
        var out = template.replace(ARGUMENTS, all)
        for (i in 1..9) {
            val value = args.getOrNull(i - 1) ?: ""
            out = out.replace(POSITIONAL + i, value)
        }
        return out.trim()
    }
}
