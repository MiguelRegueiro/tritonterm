package com.tritonterm.android.config

import android.content.Context
import com.tritonterm.android.R
import android.graphics.Typeface
import android.os.Build
import java.io.File

internal enum class TerminalKeyAction(val configText: String, val terminalInput: String? = null) {
    COPY_TO_CLIPBOARD("copy_to_clipboard"),
    PASTE_FROM_CLIPBOARD("paste_from_clipboard"),
    SEND_ESCAPE("send_key esc", "\u001b"),
    SEND_CTRL_C("send_text all \\x03", "\u0003");

    companion object {
        fun parse(value: String): TerminalKeyAction? = entries.firstOrNull { it.configText == value }
    }
}

internal data class TerminalKeyBinding(
    val key: String,
    val control: Boolean,
    val shift: Boolean,
    val alt: Boolean,
    val action: TerminalKeyAction
) {
    fun matches(keyName: String, control: Boolean, shift: Boolean, alt: Boolean): Boolean =
        key == keyName && this.control == control && this.shift == shift && this.alt == alt

    fun sameChord(other: TerminalKeyBinding): Boolean =
        key == other.key && control == other.control && shift == other.shift && alt == other.alt

    fun encode(): String {
        val modifiers = buildList {
            if (control) add("ctrl")
            if (shift) add("shift")
            if (alt) add("alt")
            add(key)
        }
        return "map ${modifiers.joinToString("+")} ${action.configText}"
    }

    companion object {
        fun parse(chord: String, actionText: String): TerminalKeyBinding? {
            val parts = chord.lowercase().split('+')
            if (parts.isEmpty() || parts.any(String::isEmpty)) return null
            val key = parts.last()
            if (key !in supportedKeys) return null
            val modifiers = parts.dropLast(1)
            if (modifiers.size != modifiers.toSet().size || modifiers.any { it !in setOf("ctrl", "shift", "alt") }) {
                return null
            }
            val action = TerminalKeyAction.parse(actionText) ?: return null
            return TerminalKeyBinding(
                key = key,
                control = "ctrl" in modifiers,
                shift = "shift" in modifiers,
                alt = "alt" in modifiers,
                action = action
            )
        }

        private val supportedKeys = buildSet {
            add("insert")
            add("esc")
            addAll(('a'..'z').map { it.toString() })
            addAll(('0'..'9').map { it.toString() })
            addAll(setOf("comma", "period", "minus", "plus", "space"))
        }
    }
}

internal val defaultTerminalKeyBindings = listOf(
    TerminalKeyBinding("c", control = true, shift = false, alt = false, action = TerminalKeyAction.COPY_TO_CLIPBOARD),
    TerminalKeyBinding("v", control = true, shift = false, alt = false, action = TerminalKeyAction.PASTE_FROM_CLIPBOARD),
    TerminalKeyBinding("esc", control = false, shift = false, alt = false, action = TerminalKeyAction.SEND_CTRL_C),
    TerminalKeyBinding("esc", control = true, shift = false, alt = false, action = TerminalKeyAction.SEND_ESCAPE)
)

internal data class TerminalConfig(
    val fontFamily: String = "Noto Sans Mono",
    val fontSize: Int = 32,
    val keyBindings: List<TerminalKeyBinding> = defaultTerminalKeyBindings
) {
    fun typeface(context: Context): Typeface = when (fontFamily) {
        "Hack Nerd Font Mono" -> context.resources.getFont(R.font.hack_nerd_font_mono_regular)
        "JetBrainsMono Nerd Font" -> context.resources.getFont(R.font.jetbrains_mono_nerd_font_regular)
        "Noto Sans Mono" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            context.resources.getFont(R.font.noto_sans_mono)
        else Typeface.create("sans-serif-monospace", Typeface.NORMAL)
        else -> Typeface.MONOSPACE
    }

    /** Typefaces routed explicitly by TritonTerm's terminal renderer. */
    fun boxDrawingTypeface(context: Context): Typeface? =
        if (fontFamily == "Noto Sans Mono") context.resources.getFont(R.font.hack_nerd_font_mono_regular) else null

    fun nerdTypeface(context: Context): Typeface? =
        if (fontFamily == "Noto Sans Mono") context.resources.getFont(R.font.jetbrains_mono_nerd_font_regular) else null

    fun encode(): String = buildList {
        add("# TritonTerm config. map uses Kitty-style modifier+key chords.")
        add("# config_version 6")
        add("# font_family: Noto Sans Mono, Hack Nerd Font Mono, or JetBrainsMono Nerd Font")
        add("# font_size: 12 through 112 px")
        add("# Supported map actions: copy_to_clipboard, paste_from_clipboard, send_key esc, send_text all \\x03")
        add("font_family $fontFamily")
        add("font_size $fontSize")
        addAll(keyBindings.map(TerminalKeyBinding::encode))
    }.joinToString(separator = "\n", postfix = "\n")

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
            // Move untouched Noto defaults from the old 28 px size to the new default.
            // Explicit font sizes and custom key maps stay unchanged.
            if (text.lineSequence().none { it.trim() == CONFIG_VERSION_MARKER } &&
                loaded == TerminalConfig(fontFamily = "Noto Sans Mono", fontSize = 28)
            ) {
                val migrated = TerminalConfig()
                file.writeText(migrated.encode())
                return migrated
            }
            // Migrate the untouched defaults created before Noto Sans Mono became the default.
            if (loaded == TerminalConfig("JetBrainsMono Nerd Font", 28) ||
                loaded == TerminalConfig("Hack Nerd Font Mono", 28)
            ) {
                val migrated = TerminalConfig()
                file.writeText(migrated.encode())
                return migrated
            }
            // Version 3 defaulted to Hack, changing Noto's zero. Restore Kitty's
            // Noto text face for those configs while keeping size and key maps.
            if (text.lineSequence().any { it.trim() == "# config_version 3" } &&
                loaded.fontFamily == "Hack Nerd Font Mono"
            ) {
                val migrated = loaded.copy(fontFamily = "Noto Sans Mono")
                file.writeText(migrated.encode())
                return migrated
            }
            // Existing app installs keep their config across APK updates. Migrate old
            // generated configs once, adding only missing default chords and preserving
            // any user mapping already assigned to those chords.
            if (text.lineSequence().none { it.trim() == CONFIG_VERSION_MARKER }) {
                val presentBindings = text.lineSequence().mapNotNull(::parseMapLine).toList()
                val missingDefaults = defaultTerminalKeyBindings.filterNot { default ->
                    presentBindings.any { it.sameChord(default) }
                }
                val additions = missingDefaults.map(TerminalKeyBinding::encode) + CONFIG_VERSION_MARKER
                val separator = when {
                    text.isEmpty() -> ""
                    text.endsWith("\n\n") -> ""
                    text.endsWith('\n') -> "\n"
                    else -> "\n\n"
                }
                file.writeText(text + separator + additions.joinToString("\n") + "\n")
                return loaded.copy(keyBindings = loaded.keyBindings + missingDefaults)
            }
            return loaded
        }

        private const val CONFIG_VERSION_MARKER = "# config_version 6"

        private fun parseMapLine(line: String): TerminalKeyBinding? {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 3 || fields[0] != "map") return null
            return TerminalKeyBinding.parse(fields[1], fields.drop(2).joinToString(" "))
        }

        fun parse(text: String): ConfigParseResult {
            var fontFamily = "Noto Sans Mono"
            var fontSize = 32
            val keyBindings = mutableListOf<TerminalKeyBinding>()
            var hasMapDirectives = false
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val separator = line.indexOfFirst { it.isWhitespace() }
                if (separator < 1) return ConfigParseResult(null, "Line ${index + 1}: expected an option and value.")
                val key = line.substring(0, separator)
                val value = line.substring(separator).trim()
                when (key) {
                    "map" -> {
                        hasMapDirectives = true
                        val fields = value.split(Regex("\\s+"))
                        if (fields.size < 2) {
                            return ConfigParseResult(null, "Line ${index + 1}: map expects a key chord and supported action.")
                        }
                        val binding = TerminalKeyBinding.parse(fields[0], fields.drop(1).joinToString(" "))
                            ?: return ConfigParseResult(null, "Line ${index + 1}: supported maps use ctrl/shift/alt + a key and clipboard or terminal-input actions.")
                        if (keyBindings.any { it.key == binding.key && it.control == binding.control && it.shift == binding.shift && it.alt == binding.alt }) {
                            return ConfigParseResult(null, "Line ${index + 1}: this key chord is already mapped.")
                        }
                        keyBindings += binding
                    }
                    "font_family" -> {
                        if (value !in setOf("Hack Nerd Font Mono", "JetBrainsMono Nerd Font", "Noto Sans Mono")) {
                            return ConfigParseResult(null, "Line ${index + 1}: supported fonts are Hack Nerd Font Mono, JetBrainsMono Nerd Font, and Noto Sans Mono.")
                        }
                        fontFamily = value
                    }
                    "font_size" -> {
                        val parsed = value.toIntOrNull()
                            ?: return ConfigParseResult(null, "Line ${index + 1}: font_size must be a number from 12 to 112.")
                        if (parsed !in 12..112) return ConfigParseResult(null, "Line ${index + 1}: font_size must be from 12 to 112.")
                        fontSize = parsed
                    }
                    else -> return ConfigParseResult(null, "Line ${index + 1}: '$key' is not supported yet.")
                }
            }
            return ConfigParseResult(
                TerminalConfig(fontFamily, fontSize, if (hasMapDirectives) keyBindings else defaultTerminalKeyBindings),
                null
            )
        }
    }
}

internal data class ConfigParseResult(val config: TerminalConfig?, val error: String?)
