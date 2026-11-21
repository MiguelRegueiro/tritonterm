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
    fun pendingPromptThemeMatchesTheRootfsStarshipConfiguration() {
        assertEquals(
            File("../scripts/arch-image/starship.toml").readText(),
            File("src/main/assets/tritonterm-starship-theme.toml").readText()
        )
    }

    @Test
    fun guestIdentitySmokeAssetParsesWithFish() {
        listOf("arch-guest-identity-smoke.fish", "tritonterm-fish-system-config.fish", "tritonterm-async-prompt.fish").forEach { name ->
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
    fun rootfsStarshipBenchmarkParsesWithFish() {
        val script = File("../scripts/arch-image/starship-prompt-benchmark.fish")
        assertTrue("The native rootfs prompt benchmark must be present", script.isFile)
        val process = ProcessBuilder("fish", "--no-execute", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("Fish rejected the exact benchmark script:\n$output", 0, process.waitFor())
    }

    @Test
    fun generatedPermissionProbeParsesWithFishAndUsesSiblingMetadataTree() {
        val script = ProotPermissionProbe.fishAccessScript()
        val process = ProcessBuilder("fish", "--no-execute", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("Fish rejected the generated permission probe:\n$output\n$script", 0, process.waitFor())

        val root = File("/data/user/0/com.example/files/usr/var/lib/pr/installed-rootfs/archlinuxarm")
        assertEquals(
            File(root.absolutePath + ".tritonterm-metadata/usr/bin/.proot-meta-file.id"),
            ProotPermissionProbe.sidecarPath(root, "usr/bin/id")
        )
        assertEquals(
            File(root.absolutePath + ".tritonterm-metadata/.proot-meta-file.root"),
            ProotPermissionProbe.sidecarPath(root, "")
        )
    }

    @Test
    fun packageManagerProbeParsesInFishAndComparesBothFakerootInstallations() {
        val script = ProotPermissionProbe.packageManagerScript()
        val process = ProcessBuilder("fish", "--no-execute", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("Fish rejected the generated package probe:\n$output\n$script", 0, process.waitFor())
        assertTrue(script.contains("command -s fakeroot"))
        assertTrue(script.contains("/opt/tritonterm/fakeroot-tcp/2.1.4/bin/fakeroot -- /usr/bin/id -u"))
        assertTrue(script.contains("/usr/bin/fakeroot -- /usr/bin/id -u"))
        assertTrue(script.contains("/opt/tritonterm/fakeroot-tcp/2.1.4/lib/libfakeroot.so"))
        assertTrue(script.contains("stat -L -c 'PACKAGE_PROBE_STAT=%A %a %u:%g %n'"))
        assertTrue(script.contains("PACKAGE_PROBE_NOT_EXECUTABLE"))
        assertTrue(script.contains("mirror.archlinuxarm.org"))
        assertTrue(script.contains("aur.archlinux.org"))
        assertFalse(script.contains("LD_LIBRARY_PATH="))
        listOf("bin/faked", "lib/libfakeroot.so", "lib/libfakeroot-0.so").forEach { path ->
            assertTrue("The host snapshot must include $path", ProotPermissionProbe.targets.contains("opt/tritonterm/fakeroot-tcp/2.1.4/$path"))
        }
    }

    @Test
    fun keyringRepairUsesOnlyBundledOfficialTrustAndKeepsSignatureChecksEnabled() {
        val script = ProotPermissionProbe.pacmanKeyringRepairScript()
        val process = ProcessBuilder("bash", "-n", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("Bash rejected the keyring repair script:\n$output\n$script", 0, process.waitFor())
        assertTrue(script.contains("pacman-key --init"))
        assertTrue(script.contains("pacman-key --populate archlinuxarm"))
        assertTrue(script.contains("68B3537F39A313B3E574D06777193F152BDBE6A6"))
        assertTrue(script.contains("/usr/share/pacman/keyrings/archlinuxarm-trusted"))
        assertTrue(script.contains("db.lck"))
        assertFalse(script.contains("SigLevel = Never"))
        assertFalse(script.contains("TrustAll"))
        assertFalse(script.contains("--refresh-keys"))
    }

    @Test
    fun permissionSyscallProbeParsesAndCoversBothAarch64AccessSyscalls() {
        val script = File("src/main/assets/proot-permission-syscall-probe.pl")
        assertTrue("The compact guest syscall probe must be packaged", script.isFile)
        val process = ProcessBuilder("perl", "-c", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("Perl rejected the guest syscall probe:\n$output", 0, process.waitFor())
        val source = script.readText()
        assertTrue(source.contains("48, -100"))
        assertTrue(source.contains("439, -100"))
        assertTrue(source.contains("AT_EACCESS"))
        assertTrue(source.contains("errno=%d"))
        val installer = File("src/main/java/com/tritonterm/android/terminal/ArchLinuxArmInstaller.kt").readText()
        listOf(
            "/usr/bin/id",
            "/opt/tritonterm/fakeroot-tcp/2.1.4/bin/faked",
            "/opt/tritonterm/fakeroot-tcp/2.1.4/lib/libfakeroot.so",
            "/opt/tritonterm/fakeroot-tcp/2.1.4/lib/libfakeroot-0.so"
        ).forEach { path -> assertTrue("Permission syscall probe must cover $path", installer.contains("\"$path\"")) }
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
            // Android's host filesystem cannot resolve this absolute guest symlink
            // against the rootfs. Readiness must inspect its directory entry without
            // following it; the PRoot smoke executes it later in the guest namespace.
            File(root, "usr/bin/rustc").delete()
            File(root, "usr/bin/cargo").delete()
            Files.createSymbolicLink(File(root, "usr/bin/rustc").toPath(), Paths.get("/usr/bin/rustup"))
            Files.createSymbolicLink(File(root, "usr/bin/cargo").toPath(), Paths.get("/usr/bin/rustup"))
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
    fun rustupSymlinkReadinessDoesNotResolveAbsoluteTargetsOnAndroidHost() {
        val root = Files.createTempDirectory("arch-dev-rust-links").toFile()
        try {
            val rustc = File(root, "usr/bin/rustc")
            rustc.parentFile?.mkdirs()
            Files.createSymbolicLink(rustc.toPath(), Paths.get("/__tritonterm_guest_only__/usr/bin/rustup"))
            assertTrue(Files.exists(rustc.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertFalse("Android host lookup must not resolve this guest-absolute path", rustc.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun androidTimezoneAndUtf8LocaleAreSampledForEveryNewGuestSession() {
        val previous = java.util.TimeZone.getDefault()
        val previousProperty = System.getProperty("user.timezone")
        try {
            System.setProperty("user.timezone", "Europe/Madrid")
            assertEquals("Europe/Madrid", ArchGuestEnvironment.variables()["TZ"])
            assertEquals("C.UTF-8", ArchGuestEnvironment.variables()["LANG"])
            System.setProperty("user.timezone", "Pacific/Auckland")
            assertEquals("Pacific/Auckland", ArchGuestEnvironment.variables()["TZ"])
            assertEquals("C.UTF-8", ArchGuestEnvironment.variables()["LANG"])
        } finally {
            if (previousProperty == null) System.clearProperty("user.timezone")
            else System.setProperty("user.timezone", previousProperty)
            java.util.TimeZone.setDefault(previous)
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
    fun reviewApkCanInstallBesideTheExistingAppWithoutChangingNormalApplicationId() {
        val gradleConfig = File("build.gradle.kts").readText()
        val apkBuilder = File("../scripts/build-apk.sh").readText()
        assertTrue(gradleConfig.contains("tritonterm.androidApplicationIdSuffix"))
        assertTrue(gradleConfig.contains("getByName(\"debug\")"))
        assertTrue(apkBuilder.contains("TRITONTERM_ANDROID_APPLICATION_ID_SUFFIX"))
        assertTrue(gradleConfig.contains("applicationId = \"com.tritonterm.android\""))
    }

    @Test
    fun everyAndroidProotEntryPointSelfHealsTheAppTempDirectory() {
        val installer = File("src/main/java/com/tritonterm/android/terminal/ArchLinuxArmInstaller.kt").readText()
        val terminal = File("src/main/java/com/tritonterm/android/terminal/TerminalSessionController.kt").readText()
        val diagnostics = File("src/main/java/com/tritonterm/android/terminal/TerminalDiagnostics.kt").readText()

        assertTrue(installer.contains("ProotTempDirectory.forApp(context)"))
        assertTrue(terminal.contains("ProotTempDirectory.forApp(context)"))
        assertTrue(diagnostics.contains("env[\"PROOT_TMP_DIR\"]?.let { ProotTempDirectory.ensure(File(it)) }"))
        assertTrue(diagnostics.contains("ProotTempDirectory.forApp(context)"))
        assertFalse(installer.contains("File(context.cacheDir, \"proot\")"))
        assertFalse(terminal.contains("File(context.cacheDir, \"proot\")"))
    }

    @Test
    fun installedRootfsRetryRunsChecksWithoutReenteringDownloadOrExtraction() {
        val installer = File("src/main/java/com/tritonterm/android/terminal/ArchLinuxArmInstaller.kt").readText()
        val installedBranch = installer.substringAfter("if (ArchLinuxArmBootstrap.isInstalled(appContext)) {")
            .substringBefore("\n        }\n        return runSingleFlight")
        assertTrue(installedBranch.contains("runSingleFlight(appContext, \"setup\")"))
        assertTrue(installedBranch.contains("verifyDeveloperImage(app)"))
        assertTrue(installedBranch.contains("requireTcpFakeroot(featureWarnings)"))
        assertTrue(installedBranch.contains("requireRustToolchain(featureWarnings)"))
        assertTrue(installedBranch.contains("initializePacmanKeys(app)"))
        assertTrue(installedBranch.contains("signing keys could not be initialized and verified"))
        assertTrue(installedBranch.contains("rootfs_reused"))
        assertFalse(installedBranch.contains("ArchLinuxArmBootstrap.prepare"))
        assertFalse(installedBranch.contains("install\", ArchLinuxArmBootstrap.DISTRO_ALIAS"))
        assertEquals(
            "Fresh install and reuse paths must both gate readiness on TCP fakeroot verification",
            2,
            Regex("requireTcpFakeroot\\(featureWarnings\\)").findAll(installer).count()
        )
        assertEquals(2, Regex("requireRustToolchain\\(featureWarnings\\)").findAll(installer).count())
    }

    @Test
    fun freshSetupAutomaticallyInitializesKeyringAndRequiresTcpFakerootBeforeReady() {
        val installer = File("src/main/java/com/tritonterm/android/terminal/ArchLinuxArmInstaller.kt").readText()
        val freshBranch = installer.substringAfter("return runSingleFlight(appContext, \"setup\") { app ->")
            .substringBefore("\n        }\n    }")
        val keyring = freshBranch.indexOf("initializePacmanKeys(app)")
        val fakeroot = freshBranch.indexOf("requireTcpFakeroot(featureWarnings)")
        assertTrue("fresh setup must automatically initialize and verify the official keyring", keyring >= 0)
        assertTrue("fresh setup must verify TCP fakeroot", fakeroot > keyring)
        assertTrue(
            "fresh setup cannot report ready before required checks",
            freshBranch.indexOf("\"setup\", \"completed\"") > fakeroot
        )

        val builder = File("../scripts/build-arch-dev-rootfs.sh").readText()
        assertTrue(builder.contains("--acls \\\n    --use-compress-program='gzip -1'"))
        assertFalse("the Android Rust tar extractor cannot decode GNU sparse entries", builder.contains("--acls --sparse"))
        assertTrue(
            "the packaged Fish login smoke must receive the Android guest locale and timezone",
            builder.contains("env LANG=C.UTF-8 TZ=Europe/Madrid /usr/bin/fish -l -i -c")
        )
        val workflow = File("../.github/workflows/build-arch-dev-rootfs.yml").readText()
        assertTrue(workflow.contains("extract_arch_fakeroot_payload -- --ignored --nocapture"))
    }

    @Test
    fun installerPinsUpstreamInputsAndUsesRestrictivePackageAuthorization() {
        val script = File("../scripts/arch-image/setup-arch-developer.sh").readText()
        val paruBuild = File("../scripts/arch-image/paru-bootstrap.PKGBUILD").readText()
        val prootPreparation = File("../scripts/prepare-proot-source.sh").readText()
        val prootGuestEnvPatch = File("../scripts/patches/tritonterm-arch-guest-env.patch").readText()
        val installer = File("src/main/java/com/tritonterm/android/terminal/ArchLinuxArmInstaller.kt").readText()
        val smoke = File("src/main/assets/arch-guest-identity-smoke.fish").readText()
        assertTrue(script.contains(ArchDeveloperEnvironment.SOURCE_SHA256))
        assertTrue(script.contains("--with-ipc=tcp"))
        assertTrue(script.contains("export SYSTEMD_OFFLINE=1"))
        assertTrue(script.contains("SYSTEMD_OFFLINE=1"))
        assertTrue(prootPreparation.contains("tritonterm-arch-guest-env.patch"))
        assertTrue(prootGuestEnvPatch.contains("TRITONTERM_GUEST_LOCALE_TZ_V1"))
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
        assertTrue(smoke.contains("check_executable mkdir /usr/bin/mkdir"))
        assertTrue(smoke.contains("check_executable uname /usr/bin/uname"))
        assertTrue(smoke.contains("check_executable sudo /usr/bin/sudo"))
        assertTrue(smoke.contains("check_executable starship /usr/bin/starship"))
        assertTrue(smoke.contains("check_executable rustc /usr/bin/rustc"))
        assertTrue(smoke.contains("check_executable cargo /usr/bin/cargo"))
        assertTrue(smoke.contains("rustup_cargo_build_and_run"))
        assertTrue("Rust smoke executables must be built in the guest user's home, not PRoot's host-bound /tmp", smoke.contains("mktemp -d \"\$rust_smoke_cache/tritonterm-rust-smoke.XXXXXX\""))
        assertFalse("Do not put executable Rust smoke artifacts in PRoot's host-bound /tmp", smoke.contains("mktemp -d /tmp/tritonterm-rust-smoke"))
        assertTrue(smoke.contains("locale_charmap UTF-8"))
        assertTrue(smoke.contains("timezone Android timezone was not passed into the guest"))
        assertTrue(smoke.contains("starship module time"))
        val terminal = File("src/main/java/com/tritonterm/android/terminal/TerminalSessionController.kt").readText()
        assertTrue(terminal.contains("LANG=${'$'}{ArchGuestEnvironment.LANG}"))
        assertTrue(terminal.contains("TZ=${'$'}{ArchGuestEnvironment.timezoneId()}"))
        assertTrue(installer.contains("put(\"LANG\", ArchGuestEnvironment.LANG)"))
        assertTrue(installer.contains("put(\"TZ\", ArchGuestEnvironment.timezoneId())"))
        assertTrue(smoke.contains("check_command_resolution id"))
        assertTrue(smoke.contains("check_command_resolution mkdir"))
        assertTrue(smoke.contains("check_command_resolution uname"))
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
        assertTrue(smoke.contains("CHECK_OK fish_greeting_suppressed"))
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
