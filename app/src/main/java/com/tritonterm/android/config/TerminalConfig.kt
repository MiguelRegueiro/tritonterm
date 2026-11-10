package com.tritonterm.android.config

import android.content.Context
import com.tritonterm.android.R
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.Typeface
import android.os.Build
import java.io.File

internal data class TerminalConfig(
    val fontFamily: String = "Noto Sans Mono",
    val fontSize: Int = 28
) {
    fun typeface(context: Context): Typeface = when (fontFamily) {
        "Hack Nerd Font Mono" -> context.resources.getFont(R.font.hack_nerd_font_mono_regular)
        "JetBrainsMono Nerd Font" -> context.resources.getFont(R.font.jetbrains_mono_nerd_font_regular)
        "Noto Sans Mono" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val primary = FontFamily.Builder(Font.Builder(context.resources, R.font.noto_sans_mono).build()).build()
            val nerdFallback = FontFamily.Builder(
                Font.Builder(context.resources, R.font.hack_nerd_font_mono_regular).build()
            ).build()
            Typeface.CustomFallbackBuilder(primary).addCustomFallback(nerdFallback).build()
        } else {
            Typeface.create("sans-serif-monospace", Typeface.NORMAL)
        }
        else -> Typeface.MONOSPACE
    }

    fun encode(): String = """
        # TritonTerm config. Only the listed options are supported today.
        # font_family: Noto Sans Mono, Hack Nerd Font Mono, or JetBrainsMono Nerd Font
        # font_size: 12 through 40
        font_family $fontFamily
        font_size $fontSize
    """.trimIndent() + "\n"

    companion object {
        fun file(home: File): File = File(home, ".config/tritonterm/tritonterm.conf")

        fun loadOrCreate(file: File): TerminalConfig {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(TerminalConfig().encode())
                return TerminalConfig()
            }
            val text = file.readText()
            val loaded = parse(text).config ?: return TerminalConfig()
            // Migrate the untouched defaults created before Noto Sans Mono became the default.
            if (loaded == TerminalConfig("JetBrainsMono Nerd Font", 28) ||
                loaded == TerminalConfig("Hack Nerd Font Mono", 28)
            ) {
                val migrated = TerminalConfig()
                file.writeText(migrated.encode())
                return migrated
            }
            return loaded
        }

        fun parse(text: String): ConfigParseResult {
            var fontFamily = "Noto Sans Mono"
            var fontSize = 28
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val separator = line.indexOfFirst { it.isWhitespace() }
                if (separator < 1) return ConfigParseResult(null, "Line ${index + 1}: expected an option and value.")
                val key = line.substring(0, separator)
                val value = line.substring(separator).trim()
                when (key) {
                    "font_family" -> {
                        if (value !in setOf("Hack Nerd Font Mono", "JetBrainsMono Nerd Font", "Noto Sans Mono")) {
                            return ConfigParseResult(null, "Line ${index + 1}: supported fonts are Hack Nerd Font Mono, JetBrainsMono Nerd Font, and Noto Sans Mono.")
                        }
                        fontFamily = value
                    }
                    "font_size" -> {
                        val parsed = value.toIntOrNull()
                            ?: return ConfigParseResult(null, "Line ${index + 1}: font_size must be a number from 12 to 40.")
                        if (parsed !in 12..40) return ConfigParseResult(null, "Line ${index + 1}: font_size must be from 12 to 40.")
                        fontSize = parsed
                    }
                    else -> return ConfigParseResult(null, "Line ${index + 1}: '$key' is not supported yet.")
                }
            }
            return ConfigParseResult(TerminalConfig(fontFamily, fontSize), null)
        }
    }
}

internal data class ConfigParseResult(val config: TerminalConfig?, val error: String?)
