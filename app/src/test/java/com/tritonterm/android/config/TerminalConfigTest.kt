package com.tritonterm.android.config

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalConfigTest {
    @Test
    fun defaultsToKittyTextFaceWithNerdSymbols() {
        assertEquals("Noto Sans Mono", TerminalConfig().fontFamily)
        assertEquals(32, TerminalConfig().fontSize)
    }

    @Test
    fun migratesUntouchedTwentyEightPixelDefaultToThirtyTwo() {
        val file = temporaryConfig(
            TerminalConfig(fontFamily = "Noto Sans Mono", fontSize = 28)
                .encode()
                .replace("# config_version 7", "# config_version 5")
        )

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals(32, loaded.fontSize)
        assertEquals(defaultTerminalKeyBindings, loaded.keyBindings)
        assertTrue(file.readText().contains("font_size 32"))
        assertTrue(file.readText().contains("# config_version 7"))
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
            .replace("# config_version 7", "# config_version 6")
        val file = temporaryConfig(oldConfig)

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals(defaultTerminalKeyBindings, loaded.keyBindings)
        assertTrue(file.readText().contains("# config_version 7"))
        assertTrue(file.readText().contains("map --when-focus-on var:elio ctrl+c send_key ctrl+c"))
        assertTrue(file.readText().contains("map --when-focus-on var:in_editor ctrl+shift+tab send_key f14"))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun migratesVersionThreeHackDefaultBackToNotoAndPreservesSizeAndKeyMaps() {
        val file = temporaryConfig(
            TerminalConfig(fontFamily = "Hack Nerd Font Mono", fontSize = 31)
                .encode()
                .replace("# config_version 7", "# config_version 3")
        )

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals("Noto Sans Mono", loaded.fontFamily)
        assertEquals(31, loaded.fontSize)
        assertEquals(defaultTerminalKeyBindings, loaded.keyBindings)
        assertTrue(file.readText().contains("# config_version 7"))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun migratesVersionTwoNotoConfigWithoutChangingItsFont() {
        val file = temporaryConfig(
            TerminalConfig(fontFamily = "Noto Sans Mono", fontSize = 30)
                .encode()
                .replace("# config_version 7", "# config_version 2")
        )

        val loaded = TerminalConfig.loadOrCreate(file)

        assertEquals("Noto Sans Mono", loaded.fontFamily)
        assertEquals(30, loaded.fontSize)
        assertTrue(file.readText().contains("# config_version 7"))
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun preservesAnExplicitNotoSelectionInCurrentConfigVersion() {
        val original = TerminalConfig(fontFamily = "Noto Sans Mono", fontSize = 30)
        val file = temporaryConfig(original.encode())

        assertEquals(original, TerminalConfig.loadOrCreate(file))
        file.parentFile?.deleteRecursively()
    }

    private fun temporaryConfig(contents: String): File {
        val directory = Files.createTempDirectory("tritonterm-terminal-config").toFile()
        return File(directory, "tritonterm.conf").apply { writeText(contents) }
    }
}
