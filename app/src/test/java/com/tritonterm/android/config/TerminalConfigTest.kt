package com.tritonterm.android.config

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalConfigTest {
    @Test
    fun selectsAndPersistsArchRuntime() {
        for (runtime in listOf("proot", "chroot-ng")) {
            val parsed = TerminalConfig.parse("arch_runtime $runtime").config!!
            assertEquals(runtime, parsed.archRuntime)
            assertEquals(runtime, TerminalConfig.parse(parsed.encode()).config?.archRuntime)
        }
        assertTrue(TerminalConfig.parse("arch_runtime unknown").error != null)
    }

    @Test
    fun defaultsToKittyTextFaceWithNerdSymbols() {
        assertEquals("Noto Sans Mono", TerminalConfig().fontFamily)
        assertEquals(32, TerminalConfig().fontSize)
        assertEquals("beam", TerminalConfig().cursorShape)
        assertEquals(3.5, TerminalConfig().cursorBeamThickness, 0.0)
        assertEquals(0.0, TerminalConfig().cursorBlinkInterval, 0.0)
        assertEquals("#cccccc", TerminalConfig().cursorColor)
        assertEquals("#111111", TerminalConfig().cursorTextColor)
    }

    @Test
    fun migratesUntouchedTwentyEightPixelDefaultToThirtyTwo() {
        val file = temporaryConfig(
            TerminalConfig(fontFamily = "Noto Sans Mono", fontSize = 28)
                .encode()
                .replace("# config_version 11", "# config_version 5")
        )

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals(32, loaded.fontSize)
        assertEquals(defaultTerminalKeyBindings, loaded.keyBindings)
        assertTrue(file.readText().contains("font_size 32"))
        assertTrue(file.readText().contains("# config_version 11"))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun acceptsFontSizesThroughOneHundredTwelveAndRejectsLargerValues() {
        assertEquals(112, TerminalConfig.parse("font_size 112").config?.fontSize)
        assertEquals(12, TerminalConfig.parse("font_size 12").config?.fontSize)
        assertTrue(TerminalConfig.parse("font_size 113").error != null)
    }

    @Test
    fun parsesKittyConditionalMapAndOnlyMatchesWhenItsVariableIsActive() {
        val result = TerminalConfig.parse(
            "map --when-focus-on var:in_editor ctrl+shift+tab send_key f14"
        )
        val binding = result.config?.keyBindings?.single()

        assertEquals(null, result.error)
        assertEquals("map --when-focus-on var:in_editor ctrl+shift+tab send_key f14", binding?.encode())
        assertFalse(binding!!.matches("tab", control = true, shift = true, alt = false))
        assertTrue(binding.matches("tab", control = true, shift = true, alt = false, setOf("in_editor")))
        assertFalse(binding.matches("tab", control = true, shift = true, alt = false, setOf("elio")))
    }

    @Test
    fun migratesVersionSixConfigByAddingConditionalElioAndEditorMaps() {
        val oldConfig = TerminalConfig(keyBindings = defaultTerminalKeyBindings.take(4))
            .encode()
            .replace("# config_version 11", "# config_version 6")
        val file = temporaryConfig(oldConfig)

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals(defaultTerminalKeyBindings, loaded.keyBindings)
        assertTrue(file.readText().contains("# config_version 11"))
        assertTrue(file.readText().contains("map --when-focus-on var:elio ctrl+c send_key ctrl+c"))
        assertTrue(file.readText().contains("map --when-focus-on var:in_editor ctrl+shift+tab send_key f14"))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun migratesVersionThreeHackDefaultBackToNotoAndPreservesSizeAndKeyMaps() {
        val file = temporaryConfig(
            TerminalConfig(fontFamily = "Hack Nerd Font Mono", fontSize = 31)
                .encode()
                .replace("# config_version 11", "# config_version 3")
        )

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals("Noto Sans Mono", loaded.fontFamily)
        assertEquals(31, loaded.fontSize)
        assertEquals(defaultTerminalKeyBindings, loaded.keyBindings)
        assertTrue(file.readText().contains("# config_version 11"))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun migratesVersionTwoNotoConfigWithoutChangingItsFont() {
        val file = temporaryConfig(
            TerminalConfig(fontFamily = "Noto Sans Mono", fontSize = 30)
                .encode()
                .replace("# config_version 11", "# config_version 2")
        )

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals("Noto Sans Mono", loaded.fontFamily)
        assertEquals(30, loaded.fontSize)
        assertTrue(file.readText().contains("# config_version 11"))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun preservesAnExplicitNotoSelectionInCurrentConfigVersion() {
        val original = TerminalConfig(fontFamily = "Noto Sans Mono", fontSize = 30)
        val file = temporaryConfig(original.encode())

        assertEquals(original, TerminalConfig.loadOrCreate(file))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun parsesKittyCursorShapeBlinkColorsAndThickness() {
        val parsed = TerminalConfig.parse(
            "cursor_shape beam\ncursor_blink_interval 0.5\ncursor #DEA016\ncursor_text_color #111111\ncursor_beam_thickness 2\ncursor_underline_thickness 3"
        )

        assertEquals(null, parsed.error)
        assertEquals("beam", parsed.config?.cursorShape)
        assertEquals(0.5, parsed.config?.cursorBlinkInterval ?: -1.0, 0.0)
        assertEquals("#dea016", parsed.config?.cursorColor)
        assertEquals(2.0, parsed.config?.cursorBeamThickness ?: -1.0, 0.0)
        assertEquals(3.0, parsed.config?.cursorUnderlineThickness ?: -1.0, 0.0)
    }

    @Test
    fun rejectsUnsupportedCursorSettings() {
        assertTrue(TerminalConfig.parse("cursor_shape hollow").error != null)
        assertTrue(TerminalConfig.parse("cursor_blink_interval 0.05").error != null)
        assertTrue(TerminalConfig.parse("cursor #12345").error != null)
        assertTrue(TerminalConfig.parse("cursor_beam_thickness 0").error != null)
    }

    @Test
    fun migratesVersionSevenConfigWithKittyCursorDefaults() {
        val original = TerminalConfig(fontSize = 34, cursorShape = "beam")
        val file = temporaryConfig(original.encode().replace("# config_version 11", "# config_version 7"))

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals(34, loaded.fontSize)
        assertEquals("beam", loaded.cursorShape)
        assertTrue(file.readText().contains("cursor_blink_interval 0"))
        assertTrue(file.readText().contains("# config_version 11"))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun migratesVersionEightGeneratedBlockCursorToKittyPromptBeam() {
        val file = temporaryConfig(
            TerminalConfig().encode()
                .replace("cursor_shape beam", "cursor_shape block")
                .replace("# config_version 11", "# config_version 8")
                .replace("cursor_beam_thickness 3.5", "cursor_beam_thickness 1.5")
        )

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals("beam", loaded.cursorShape)
        assertTrue(file.readText().contains("cursor_shape beam"))
        assertTrue(file.readText().contains("# config_version 11"))
        assertTrue(file.readText().contains("cursor_beam_thickness 3.5"))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun versionEightMigrationPreservesExplicitNonDefaultCursorShape() {
        val file = temporaryConfig(
            TerminalConfig(cursorShape = "underline").encode()
                .replace("# config_version 11", "# config_version 8")
        )

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals("underline", loaded.cursorShape)
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun migratesVersionNineDefaultBeamThicknessButPreservesCustomThickness() {
        val file = temporaryConfig(
            TerminalConfig(cursorBeamThickness = 2.5).encode()
                .replace("# config_version 11", "# config_version 10")
        )

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals(3.5, loaded.cursorBeamThickness, 0.0)
        assertTrue(file.readText().contains("cursor_beam_thickness 3.5"))
        assertTrue(file.readText().contains("# config_version 11"))
        file.parentFile?.deleteRecursively()

        val customFile = temporaryConfig(
            TerminalConfig(cursorBeamThickness = 3.0).encode()
                .replace("# config_version 11", "# config_version 10")
        )

        val custom = TerminalConfig.loadOrCreate(customFile)

        assertEquals(3.0, custom.cursorBeamThickness, 0.0)
        customFile.parentFile?.deleteRecursively()
    }

    private fun temporaryConfig(contents: String): File {
        val directory = Files.createTempDirectory("tritonterm-terminal-config").toFile()
        return File(directory, "tritonterm.conf").apply { writeText(contents) }
    }
}
