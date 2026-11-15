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
    SEND_CTRL_C("send_text all \\x03", "\u0003"),
    SEND_KEY_CTRL_C("send_key ctrl+c", "\u0003"),
    SEND_KEY_F13("send_key f13", "\u001b[25~"),
    SEND_KEY_F14("send_key f14", "\u001b[26~");

    companion object {
        fun parse(value: String): TerminalKeyAction? = entries.firstOrNull { it.configText == value }
    }
}

internal data class TerminalKeyBinding(
    val key: String,
    val control: Boolean,
    val shift: Boolean,
    val alt: Boolean,
    val action: TerminalKeyAction,
    val focusVariable: String? = null
) {
    fun matches(
        keyName: String,
        control: Boolean,
        shift: Boolean,
        alt: Boolean,
        activeUserVariables: Set<String> = emptySet()
    ): Boolean = key == keyName && this.control == control && this.shift == shift &&
        this.alt == alt && (focusVariable == null || focusVariable in activeUserVariables)

    fun sameTarget(other: TerminalKeyBinding): Boolean = key == other.key &&
        control == other.control && shift == other.shift && alt == other.alt &&
        focusVariable == other.focusVariable

    fun encode(): String {
        val modifiers = buildList {
            if (control) add("ctrl")
            if (shift) add("shift")
            if (alt) add("alt")
            add(key)
        }
        val focusCondition = focusVariable?.let { "--when-focus-on var:$it " }.orEmpty()
        return "map $focusCondition${modifiers.joinToString("+")} ${action.configText}"
    }

    companion object {
        fun parse(chord: String, actionText: String, focusVariable: String? = null): TerminalKeyBinding? {
            val parts = chord.lowercase().split('+')
            if (parts.isEmpty() || parts.any(String::isEmpty)) return null
            val key = parts.last()
            if (key !in supportedKeys) return null
            val modifiers = parts.dropLast(1)
            if (modifiers.size != modifiers.toSet().size || modifiers.any { it !in setOf("ctrl", "shift", "alt") }) {
                return null
            }
            if (focusVariable != null && !validVariableName(focusVariable)) return null
            val action = TerminalKeyAction.parse(actionText) ?: return null
            return TerminalKeyBinding(
                key = key,
                control = "ctrl" in modifiers,
                shift = "shift" in modifiers,
                alt = "alt" in modifiers,
                action = action,
                focusVariable = focusVariable
            )
        }

        fun parseMapFields(fields: List<String>): TerminalKeyBinding? {
            if (fields.isEmpty()) return null
            val focusVariable: String?
            val chordIndex: Int
            if (fields[0] == "--when-focus-on") {
                if (fields.size < 4 || !fields[1].startsWith("var:")) return null
                focusVariable = fields[1].removePrefix("var:")
                chordIndex = 2
            } else {
                focusVariable = null
                chordIndex = 0
            }
            if (fields.size <= chordIndex + 1) return null
            return parse(
                fields[chordIndex],
                fields.drop(chordIndex + 1).joinToString(" "),
                focusVariable
            )
        }

        private fun validVariableName(value: String): Boolean =
            value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))

        private val supportedKeys = buildSet {
            add("insert")
            add("esc")
            add("tab")
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
    TerminalKeyBinding("esc", control = true, shift = false, alt = false, action = TerminalKeyAction.SEND_ESCAPE),
    TerminalKeyBinding("c", control = true, shift = false, alt = false, action = TerminalKeyAction.SEND_KEY_CTRL_C, focusVariable = "elio"),
    TerminalKeyBinding("esc", control = false, shift = false, alt = false, action = TerminalKeyAction.SEND_ESCAPE, focusVariable = "elio"),
    TerminalKeyBinding("tab", control = true, shift = false, alt = false, action = TerminalKeyAction.SEND_KEY_F13, focusVariable = "in_editor"),
    TerminalKeyBinding("tab", control = true, shift = true, alt = false, action = TerminalKeyAction.SEND_KEY_F14, focusVariable = "in_editor")
)

internal data class TerminalConfig(
    val fontFamily: String = "Noto Sans Mono",
    val fontSize: Int = 32,
    val keyBindings: List<TerminalKeyBinding> = defaultTerminalKeyBindings,
    val cursorShape: String = "beam",
    val cursorBlinkInterval: Double = 0.0,
    val cursorColor: String = "#cccccc",
    val cursorTextColor: String = "#111111",
    val cursorBeamThickness: Double = 3.5,
    val cursorUnderlineThickness: Double = 2.0
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
        add("# config_version 11")
        add("# font_family: Noto Sans Mono, Hack Nerd Font Mono, or JetBrainsMono Nerd Font")
        add("# font_size: 12 through 112 px")
        add("# cursor_shape: block, beam, or underline")
        add("# cursor_blink_interval: 0 disables blinking; otherwise seconds (0.1 through 2.0)")
        add("# cursor and cursor_text_color: Kitty-style #RRGGBB colors")
        add("# cursor_beam_thickness and cursor_underline_thickness: pixels at 32 px font size; scale with zoom")
        add("# Supported map actions: copy_to_clipboard, paste_from_clipboard, send_key esc, send_key ctrl+c, send_key f13, send_key f14, send_text all \\x03")
        add("# Conditional maps support Kitty syntax: map --when-focus-on var:name chord action")
        add("font_family $fontFamily")
        add("font_size $fontSize")
        add("cursor_shape $cursorShape")
        add("cursor_blink_interval ${cursorBlinkInterval.formatConfigNumber()}")
        add("cursor $cursorColor")
        add("cursor_text_color $cursorTextColor")
        add("cursor_beam_thickness ${cursorBeamThickness.formatConfigNumber()}")
        add("cursor_underline_thickness ${cursorUnderlineThickness.formatConfigNumber()}")
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
                loaded.fontFamily == "Noto Sans Mono" && loaded.fontSize == 28 &&
                loaded.keyBindings.isUntouchedDefaultSet()
            ) {
                val migrated = TerminalConfig()
                file.writeText(migrated.encode())
                return migrated
            }
            // Migrate the untouched defaults created before Noto Sans Mono became the default.
            if (loaded.fontFamily in setOf("JetBrainsMono Nerd Font", "Hack Nerd Font Mono") &&
                loaded.fontSize == 28 && loaded.keyBindings.isUntouchedDefaultSet()
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
            // Kitty shell integration uses a beam at shell prompts. Version 8
            // generated block as the default, so migrate only that default and
            // retain any other cursor shape chosen by the user.
            if (text.lineSequence().any { it.trim() == "# config_version 8" }) {
                val migratedText = text.lineSequence().map { line ->
                    when (line.trim()) {
                        "# config_version 8" -> CONFIG_VERSION_MARKER
                        "cursor_shape block" -> line.replace("block", "beam")
                        "cursor_beam_thickness 1.5" -> line.replace("1.5", "3.5")
                        else -> line
                    }
                }.joinToString("\n") + if (text.endsWith('\n')) "\n" else ""
                file.writeText(migratedText)
                return parse(migratedText).config ?: loaded
            }
            // Existing app installs keep their config across APK updates. Migrate old
            // generated configs once, adding only missing default chords and preserving
            // any user mapping already assigned to those chords.
            if (text.lineSequence().any { it.trim() == "# config_version 9" }) {
                val migratedText = text.lineSequence().map { line ->
                    when (line.trim()) {
                        "# config_version 9" -> CONFIG_VERSION_MARKER
                        "cursor_beam_thickness 1.5" -> line.replace("1.5", "3.5")
                        else -> line
                    }
                }.joinToString("\n") + if (text.endsWith('\n')) "\n" else ""
                file.writeText(migratedText)
                return parse(migratedText).config ?: loaded
            }
            // Version 10 shipped the thinner 2.5 px reference beam. Upgrade only
            // that generated default; preserve any thickness the user selected.
            if (text.lineSequence().any { it.trim() == "# config_version 10" }) {
                val migratedText = text.lineSequence().map { line ->
                    when (line.trim()) {
                        "# config_version 10" -> CONFIG_VERSION_MARKER
                        "cursor_beam_thickness 2.5" -> line.replace("2.5", "3.5")
                        else -> line
                    }
                }.joinToString("\n") + if (text.endsWith('\n')) "\n" else ""
                file.writeText(migratedText)
                return parse(migratedText).config ?: loaded
            }
            if (text.lineSequence().none { it.trim() == CONFIG_VERSION_MARKER }) {
                val presentBindings = text.lineSequence().mapNotNull(::parseMapLine).toList()
                val missingDefaults = defaultTerminalKeyBindings.filterNot { default ->
                    presentBindings.any { it.sameTarget(default) }
                }
                val parsed = parse(text).config ?: loaded
                val existingOptions = text.lineSequence().mapNotNull { line ->
                    line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
                        ?.substringBefore(' ')
                }.toSet()
                val cursorDefaults = TerminalConfig()
                val cursorLines = buildList {
                    if ("cursor_shape" !in existingOptions) add("cursor_shape ${cursorDefaults.cursorShape}")
                    if ("cursor_blink_interval" !in existingOptions) add("cursor_blink_interval ${cursorDefaults.cursorBlinkInterval.formatConfigNumber()}")
                    if ("cursor" !in existingOptions) add("cursor ${cursorDefaults.cursorColor}")
                    if ("cursor_text_color" !in existingOptions) add("cursor_text_color ${cursorDefaults.cursorTextColor}")
                    if ("cursor_beam_thickness" !in existingOptions) add("cursor_beam_thickness ${cursorDefaults.cursorBeamThickness.formatConfigNumber()}")
                    if ("cursor_underline_thickness" !in existingOptions) add("cursor_underline_thickness ${cursorDefaults.cursorUnderlineThickness.formatConfigNumber()}")
                }
                val additions = missingDefaults.map(TerminalKeyBinding::encode) + cursorLines + CONFIG_VERSION_MARKER
                val separator = when {
                    text.isEmpty() -> ""
                    text.endsWith("\n\n") -> ""
                    text.endsWith('\n') -> "\n"
                    else -> "\n\n"
                }
                file.writeText(text + separator + additions.joinToString("\n") + "\n")
                return parsed.copy(keyBindings = parsed.keyBindings + missingDefaults)
            }
            return loaded
        }

        private const val CONFIG_VERSION_MARKER = "# config_version 11"
        private val LEGACY_DEFAULT_KEY_BINDINGS = listOf(
            TerminalKeyBinding("c", true, false, false, TerminalKeyAction.COPY_TO_CLIPBOARD),
            TerminalKeyBinding("v", true, false, false, TerminalKeyAction.PASTE_FROM_CLIPBOARD),
            TerminalKeyBinding("esc", false, false, false, TerminalKeyAction.SEND_CTRL_C),
            TerminalKeyBinding("esc", true, false, false, TerminalKeyAction.SEND_ESCAPE)
        )

        private fun List<TerminalKeyBinding>.isUntouchedDefaultSet(): Boolean =
            this == LEGACY_DEFAULT_KEY_BINDINGS || this == defaultTerminalKeyBindings

        private fun parseMapLine(line: String): TerminalKeyBinding? {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 3 || fields[0] != "map") return null
            return TerminalKeyBinding.parseMapFields(fields.drop(1))
        }

        fun parse(text: String): ConfigParseResult {
            var fontFamily = "Noto Sans Mono"
            var fontSize = 32
            var cursorShape = "beam"
            var cursorBlinkInterval = 0.0
            var cursorColor = "#cccccc"
            var cursorTextColor = "#111111"
            var cursorBeamThickness = 3.5
            var cursorUnderlineThickness = 2.0
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
                        val binding = TerminalKeyBinding.parseMapFields(fields)
                            ?: return ConfigParseResult(null, "Line ${index + 1}: supported maps use Kitty-style ctrl/shift/alt chords, optional --when-focus-on var:name, and supported actions.")
                        if (keyBindings.any { it.sameTarget(binding) }) {
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
                    "cursor_shape" -> {
                        if (value !in setOf("block", "beam", "underline")) {
                            return ConfigParseResult(null, "Line ${index + 1}: cursor_shape must be block, beam, or underline.")
                        }
                        cursorShape = value
                    }
                    "cursor_blink_interval" -> {
                        val parsed = value.toDoubleOrNull()
                            ?: return ConfigParseResult(null, "Line ${index + 1}: cursor_blink_interval must be 0 or a number from 0.1 to 2.0 seconds.")
                        if (parsed != 0.0 && (parsed < 0.1 || parsed > 2.0)) {
                            return ConfigParseResult(null, "Line ${index + 1}: cursor_blink_interval must be 0 or from 0.1 to 2.0 seconds.")
                        }
                        cursorBlinkInterval = parsed
                    }
                    "cursor" -> {
                        if (!isKittyColor(value)) return ConfigParseResult(null, "Line ${index + 1}: cursor must be a #RRGGBB color.")
                        cursorColor = value.lowercase()
                    }
                    "cursor_text_color" -> {
                        if (!isKittyColor(value)) return ConfigParseResult(null, "Line ${index + 1}: cursor_text_color must be a #RRGGBB color.")
                        cursorTextColor = value.lowercase()
                    }
                    "cursor_beam_thickness", "cursor_underline_thickness" -> {
                        val parsed = value.toDoubleOrNull()
                            ?: return ConfigParseResult(null, "Line ${index + 1}: $key must be a number from 0.5 to 10 pixels.")
                        if (parsed !in 0.5..10.0) return ConfigParseResult(null, "Line ${index + 1}: $key must be from 0.5 to 10 pixels.")
                        if (key == "cursor_beam_thickness") cursorBeamThickness = parsed
                        else cursorUnderlineThickness = parsed
                    }
                    else -> return ConfigParseResult(null, "Line ${index + 1}: '$key' is not supported yet.")
                }
            }
            return ConfigParseResult(
                TerminalConfig(
                    fontFamily = fontFamily,
                    fontSize = fontSize,
                    keyBindings = if (hasMapDirectives) keyBindings else defaultTerminalKeyBindings,
                    cursorShape = cursorShape,
                    cursorBlinkInterval = cursorBlinkInterval,
                    cursorColor = cursorColor,
                    cursorTextColor = cursorTextColor,
                    cursorBeamThickness = cursorBeamThickness,
                    cursorUnderlineThickness = cursorUnderlineThickness
                ),
                null
            )
        }

        private fun isKittyColor(value: String): Boolean = value.matches(Regex("#[0-9A-Fa-f]{6}"))
    }
}

private fun Double.formatConfigNumber(): String = toString().removeSuffix(".0")

internal data class ConfigParseResult(val config: TerminalConfig?, val error: String?)
