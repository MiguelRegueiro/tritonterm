package com.tritonterm.android.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchDeveloperEnvironmentTest {
    @Test
    fun readinessRequiresTheInstalledDefaultToolchainAndUserConfiguration() {
        val root = Files.createTempDirectory("arch-dev-ready").toFile()
        try {
            val prefix = File(root, "${ArchDeveloperEnvironment.FAKEROOT_PREFIX}/${ArchDeveloperEnvironment.FAKEROOT_VERSION}")
            File(prefix, "bin").mkdirs()
            File(prefix, "lib/libfakeroot").mkdirs()
            File(prefix, "bin/fakeroot").apply { writeText("wrapper"); setExecutable(true) }
            File(prefix, "bin/faked").apply { writeText("daemon"); setExecutable(true) }
            File(prefix, "lib/libfakeroot/libfakeroot-0.so").writeText("library")
            File(prefix, ".tritonterm-version").writeText(ArchDeveloperEnvironment.FAKEROOT_VERSION)
            File(root, ArchDeveloperEnvironment.READY_MARKER)
                .apply { parentFile?.mkdirs(); writeText(ArchDeveloperEnvironment.VERSION) }
            Files.createDirectories(File(root, "etc/profile.d").toPath())
            File(root, "etc/profile.d/tritonterm-devtools.sh").writeText("PATH\nexport SYSTEMD_OFFLINE=1\n")
            File(root, "etc/sudoers.d").mkdirs()
            File(root, "etc/sudoers.d/10-tritonterm-pacman")
                .writeText("%wheel ALL=(root) NOPASSWD: /usr/bin/pacman\n")
            File(root, "etc/passwd").writeText("dev:x:1000:1000::/home/dev:/usr/bin/fish\n")
            File(root, "etc/tritonterm/selected-user").apply { parentFile?.mkdirs(); writeText("dev\n") }
            File(root, "etc/group").writeText("wheel:x:10:dev\ndev:x:1000:\n")
            val commands = listOf("makepkg", "sudo", "rustup", "rustc", "cargo", "fish", "starship", "paru", "fakeroot")
            commands.forEach { name ->
                File(root, "usr/bin/$name").apply { parentFile?.mkdirs(); writeText(name); setExecutable(true) }
            }
            File(root, "home/dev/.rustup/toolchains/stable-aarch64-unknown-linux-gnu/bin/rustc")
                .apply { parentFile?.mkdirs(); writeText("rustc"); setExecutable(true) }
            File(root, "home/dev/.config/starship.toml").apply { parentFile?.mkdirs(); writeText("config") }
            File(root, "home/dev/.config/fish/conf.d/90-tritonterm-starship.fish")
                .apply { parentFile?.mkdirs(); writeText("starship init fish") }
            File(root, "home/dev/.config/fish/conf.d/20-tritonterm-path.fish")
                .apply { parentFile?.mkdirs(); writeText("set -gx SYSTEMD_OFFLINE 1") }
            File(root, "home/dev/.config/pacman/makepkg.conf")
                .apply { parentFile?.mkdirs(); writeText("PACMAN_AUTH=(/usr/bin/sudo -n)") }
            File(root, "home/dev/.config/paru/paru.conf")
                .apply { parentFile?.mkdirs(); writeText("Sudo = /usr/bin/sudo") }
            Files.createSymbolicLink(
                File(root, "${ArchDeveloperEnvironment.FAKEROOT_PREFIX}/current").toPath(),
                Paths.get(ArchDeveloperEnvironment.FAKEROOT_VERSION)
            )
            Files.createSymbolicLink(
                File(root, "home/dev/.local/bin/fakeroot").apply { parentFile?.mkdirs() }.toPath(),
                Paths.get("/opt/tritonterm/fakeroot-tcp/current/bin/fakeroot")
            )

            assertTrue(ArchDeveloperEnvironment.isReady(root))
            File(root, "etc/profile.d/tritonterm-devtools.sh").writeText("PATH")
            assertFalse(ArchDeveloperEnvironment.isReady(root))
            File(root, "etc/profile.d/tritonterm-devtools.sh").writeText("PATH\nexport SYSTEMD_OFFLINE=1\n")
            assertTrue(ArchDeveloperEnvironment.isReady(root))
            File(root, "home/dev/.config/fish/conf.d/20-tritonterm-path.fish").writeText("PATH")
            assertFalse(ArchDeveloperEnvironment.isReady(root))
            File(root, ArchDeveloperEnvironment.READY_MARKER).writeText("0")
            assertFalse(ArchDeveloperEnvironment.isReady(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installerPinsUpstreamInputsAndUsesRestrictivePackageAuthorization() {
        val script = File("src/main/assets/setup-arch-developer.sh").readText()
        val paruBuild = File("src/main/assets/paru-bootstrap.PKGBUILD").readText()
        assertTrue(script.contains(ArchDeveloperEnvironment.SOURCE_SHA256))
        assertTrue(script.contains("--with-ipc=tcp"))
        assertTrue(script.contains("export SYSTEMD_OFFLINE=1"))
        assertTrue(script.contains("SYSTEMD_OFFLINE=1"))
        assertTrue(script.contains("command_still_running"))
        assertTrue(script.contains("pacman-transaction"))
        assertTrue(script.contains("%wheel ALL=(root) NOPASSWD: /usr/bin/pacman"))
        assertFalse(script.contains("NOPASSWD: ALL"))
        assertTrue(script.contains("makepkg --noconfirm --syncdeps --install --force"))
        assertTrue(paruBuild.contains("sha256sums=('213d55a67605ff69faefa523917fee7168ea5bf143be8cef800fb48c9759f348')"))
        assertTrue(paruBuild.contains("pkgver=2.2.0"))
    }
}
