package com.tritonterm.android.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchDeveloperEnvironmentTest {
    @Test
    fun guestIdentitySmokeAssetParsesWithFish() {
        listOf("arch-guest-identity-smoke.fish", "tritonterm-fish-system-config.fish").forEach { name ->
            val script = File("src/main/assets/$name")
            assertTrue("The $name script must be packaged as an app asset", script.isFile)
            val process = ProcessBuilder("fish", "--no-execute", script.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            assertEquals("Fish rejected the exact $name script:\n$output", 0, exitCode)
        }
    }

    @Test
    fun readinessRequiresTheInstalledDefaultToolchainAndUserConfiguration() {
        val root = Files.createTempDirectory("arch-dev-ready").toFile()
        try {
            val prefix = File(root, "${ArchDeveloperEnvironment.FAKEROOT_PREFIX}/${ArchDeveloperEnvironment.FAKEROOT_VERSION}")
            File(prefix, "bin").mkdirs()
            File(prefix, "lib").mkdirs()
            File(prefix, "bin/fakeroot").apply { writeText("wrapper"); setExecutable(true) }
            File(prefix, "bin/faked").apply { writeText("daemon"); setExecutable(true) }
            File(prefix, "lib/libfakeroot-0.so").writeText("library")
            File(prefix, "lib/libfakeroot.so").writeText("library-link")
            File(prefix, ".tritonterm-version").writeText(ArchDeveloperEnvironment.FAKEROOT_VERSION)
            File(root, ArchDeveloperEnvironment.READY_MARKER)
                .apply { parentFile?.mkdirs(); writeText(ArchDeveloperEnvironment.VERSION) }
            Files.createDirectories(File(root, "etc/profile.d").toPath())
            File(root, "etc/profile.d/tritonterm-devtools.sh").writeText("/opt/tritonterm/fakeroot-tcp/2.1.4/bin\nexport SYSTEMD_OFFLINE=1\n")
            File(root, "etc/sudoers.d").mkdirs()
            File(root, "etc/sudoers.d/10-tritonterm-pacman")
                .writeText("triton ALL=(root) NOPASSWD: /usr/bin/pacman\n")
            File(root, "etc/passwd").writeText("triton:x:1000:1000::/home/triton:/usr/bin/fish\n")
            File(root, "etc/group").writeText("wheel:x:10:triton\ntriton:x:1000:\n")
            val commands = listOf("id", "pacman", "makepkg", "sudo", "rustup", "rustc", "cargo", "fish", "starship", "paru", "fakeroot")
            commands.forEach { name ->
                File(root, "usr/bin/$name").apply { parentFile?.mkdirs(); writeText(name); setExecutable(true) }
            }
            File(root, "home/triton/.rustup/toolchains/stable-aarch64-unknown-linux-gnu/bin/rustc")
                .apply { parentFile?.mkdirs(); writeText("rustc"); setExecutable(true) }
            File(root, "home/triton/.config/starship.toml").apply { parentFile?.mkdirs(); writeText("config") }
            File(root, "etc/fish/conf.d/20-tritonterm.fish")
                .apply { parentFile?.mkdirs(); writeText("/usr/bin\n/opt/tritonterm/fakeroot-tcp/2.1.4/bin\nset -gx SYSTEMD_OFFLINE 1\nfunction fish_greeting\nend\nabbr --add up 'paru -Syu'\nstarship init fish | source") }
            File(root, "home/triton/.config/pacman/makepkg.conf")
                .apply { parentFile?.mkdirs(); writeText("PACMAN_AUTH=(/usr/bin/sudo -n)") }
            File(root, "home/triton/.config/paru/paru.conf")
                .apply { parentFile?.mkdirs(); writeText("Sudo = /usr/bin/sudo") }
            Files.createSymbolicLink(
                File(root, "${ArchDeveloperEnvironment.FAKEROOT_PREFIX}/current").toPath(),
                Paths.get(ArchDeveloperEnvironment.FAKEROOT_VERSION)
            )
            assertTrue(ArchDeveloperEnvironment.isReady(root))
            File(root, "etc/profile.d/tritonterm-devtools.sh").writeText("PATH")
            assertFalse(ArchDeveloperEnvironment.isReady(root))
            File(root, "etc/profile.d/tritonterm-devtools.sh").writeText("/opt/tritonterm/fakeroot-tcp/2.1.4/bin\nexport SYSTEMD_OFFLINE=1\n")
            assertTrue(ArchDeveloperEnvironment.isReady(root))
            File(root, "etc/fish/conf.d/20-tritonterm.fish").writeText("PATH")
            assertFalse(ArchDeveloperEnvironment.isReady(root))
            File(root, ArchDeveloperEnvironment.READY_MARKER).writeText("0")
            assertFalse(ArchDeveloperEnvironment.isReady(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun incompleteSignedImageIsRejectedWithMissingToolNames() {
        val root = Files.createTempDirectory("arch-dev-incomplete").toFile()
        try {
            File(root, ArchDeveloperEnvironment.READY_MARKER)
                .apply { parentFile?.mkdirs(); writeText(ArchDeveloperEnvironment.VERSION) }
            File(root, "etc/passwd").apply { parentFile?.mkdirs(); writeText("triton:x:1000:1000::/home/triton:/usr/bin/fish\n") }
            File(root, "etc/group").apply { parentFile?.mkdirs(); writeText("wheel:x:10:triton\ntriton:x:1000:\n") }
            val missing = ArchDeveloperEnvironment.missingRequirements(root)
            assertTrue(missing.any { it == "/usr/bin/fakeroot" })
            assertTrue(missing.any { it == "/usr/bin/fish" })
            assertTrue(missing.any { it.contains("TCP fakeroot") })
            assertFalse(ArchDeveloperEnvironment.isReady(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun terminalAccountContractIsFixedAndUnprivileged() {
        assertEquals("triton", ArchGuestUser.NAME)
        assertEquals(1000, ArchGuestUser.UID)
        assertEquals(1000, ArchGuestUser.GID)
        assertEquals("/home/triton", ArchGuestUser.HOME)
        val root = Files.createTempDirectory("triton-shell-ready").toFile()
        try {
            File(root, "etc/passwd").apply {
                parentFile?.mkdirs()
                writeText("triton:x:1000:1000::/home/triton:/usr/bin/fish\n")
            }
            File(root, "usr/bin/fish").apply { parentFile?.mkdirs(); writeText("fish"); setExecutable(true) }
            File(root, "home/triton").mkdirs()
            assertTrue("A usable Fish shell must stay available even when optional tools need repair", ArchGuestUser.hasUsableShell(root))
            File(root, "usr/bin/fish").delete()
            assertFalse(ArchGuestUser.hasUsableShell(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installerPinsUpstreamInputsAndUsesRestrictivePackageAuthorization() {
        val script = File("../scripts/arch-image/setup-arch-developer.sh").readText()
        val paruBuild = File("../scripts/arch-image/paru-bootstrap.PKGBUILD").readText()
        val installer = File("src/main/java/com/tritonterm/android/terminal/ArchLinuxArmInstaller.kt").readText()
        val smoke = File("src/main/assets/arch-guest-identity-smoke.fish").readText()
        assertTrue(script.contains(ArchDeveloperEnvironment.SOURCE_SHA256))
        assertTrue(script.contains("--with-ipc=tcp"))
        assertTrue(script.contains("export SYSTEMD_OFFLINE=1"))
        assertTrue(script.contains("SYSTEMD_OFFLINE=1"))
        assertTrue(script.contains("command_still_running"))
        assertTrue(script.contains("pacman-transaction"))
        assertTrue(script.contains("${'$'}{TRITON_USER} ALL=(root) NOPASSWD: /usr/bin/pacman"))
        assertTrue(script.contains("useradd --create-home --uid 1000 --user-group --groups wheel --shell /usr/bin/fish \"${'$'}TRITON_USER\""))
        assertFalse(script.contains("usermod --login"))
        assertFalse(script.contains("groupmod --new-name"))
        assertTrue(script.contains("sudo -n /usr/bin/pacman -Q pacman"))
        assertTrue(script.contains("tritonterm-fish-system-config.fish"))
        assertFalse(script.contains("NOPASSWD: ALL"))
        assertTrue(script.contains("makepkg --noconfirm --syncdeps --force"))
        assertTrue(script.contains("makepkg smoke archive build failed"))
        assertTrue(installer.contains("arch-guest-identity-smoke.fish"))
        assertTrue(smoke.contains("check_equals username triton \"\$USER\""))
        assertTrue(smoke.contains("check_executable id /usr/bin/id"))
        assertTrue(smoke.contains("check_executable sudo /usr/bin/sudo"))
        assertTrue(smoke.contains("check_command_resolution id"))
        assertTrue(smoke.contains("check_command_resolution sudo"))
        assertTrue(smoke.contains("check_command_resolution fakeroot"))
        assertTrue(smoke.contains("CHECK_INFO PATH=${'$'}PATH"))
        assertTrue(smoke.contains("TRITONTERM_GUEST_CHECKS_COMPLETED failures=\$failures"))
        assertTrue(smoke.contains("check_equals cwd \"\$HOME\" \"\$PWD\""))
        assertTrue(smoke.contains("echo \"CHECK_FAILED \$name expected=\$expected actual=\$actual\""))
        assertTrue(smoke.contains("/opt/tritonterm/fakeroot-tcp/2.1.4/bin/fakeroot (command -s fakeroot)"))
        assertTrue(smoke.contains("CHECK_FAILED fakeroot_fake_uid"))
        assertTrue(smoke.contains("CHECK_FAILED sudo_pacman"))
        assertTrue(smoke.contains("sudo -n /usr/bin/pacman -Dk"))
        assertTrue(smoke.contains("CHECK_FAILED pacman_database"))
        assertTrue(smoke.contains("CHECK_OK sudo_unrestricted_denied"))
        assertTrue(smoke.contains("CHECK_FAILED fish_abbreviation"))
        assertTrue(smoke.contains("CHECK_FAILED fish_starship_prompt"))
        assertFalse(installer.contains("personalize-arch-account.sh"))
        assertFalse(installer.contains("setup-arch-developer.sh"))
        assertFalse(installer.contains("pacman -Syy"))
        assertTrue(paruBuild.contains("sha256sums=('213d55a67605ff69faefa523917fee7168ea5bf143be8cef800fb48c9759f348')"))
        assertTrue(paruBuild.contains("pkgver=2.2.0"))
        assertFalse(installer.contains("personalizeDeveloperAccount"))
        assertFalse(File("src/main/assets").listFiles().orEmpty().any { it.name == "personalize-arch-account.sh" })
        assertTrue(File("src/main/java/com/tritonterm/android/terminal/TerminalSessionController.kt")
            .readText().contains("const val NAME = \"triton\""))
        assertTrue(
            "The interactive Arch launch must enable ownership metadata, as the native PRoot login test does",
            File("src/main/java/com/tritonterm/android/terminal/TerminalSessionController.kt")
                .readText().contains("TRITONTERM_PROOT_USERLAND_METADATA=1")
        )
    }
}
