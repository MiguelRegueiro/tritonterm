package com.tritonterm.android.terminal

import java.io.File

/** Paths and Fish source shared by the Android/PRoot permission diagnostic and its syntax test. */
internal object ProotPermissionProbe {
    const val FAKEROOT_PREFIX = "/opt/tritonterm/fakeroot-tcp/2.1.4"
    const val ALARM_SIGNING_FINGERPRINT = "68B3537F39A313B3E574D06777193F152BDBE6A6"
    val targets = listOf(
        "",
        "usr",
        "usr/bin",
        "usr/bin/fish",
        "usr/bin/id",
        "usr/bin/ls",
        "usr/bin/mkdir",
        "usr/bin/sudo",
        "usr/bin/starship",
        "etc/fish/conf.d/20-tritonterm.fish",
        "opt",
        "opt/tritonterm/fakeroot-tcp/2.1.4/bin",
        "opt/tritonterm/fakeroot-tcp/2.1.4/bin/fakeroot",
        "opt/tritonterm/fakeroot-tcp/2.1.4/bin/faked",
        "opt/tritonterm/fakeroot-tcp/2.1.4/lib",
        "opt/tritonterm/fakeroot-tcp/2.1.4/lib/libfakeroot.so",
        "opt/tritonterm/fakeroot-tcp/2.1.4/lib/libfakeroot-0.so"
    )

    fun sidecarPath(root: File, relative: String): File {
        val metadataRoot = File(root.absolutePath + ".tritonterm-metadata")
        if (relative.isEmpty()) return File(metadataRoot, ".proot-meta-file.root")
        val relativeFile = File(relative)
        val parent = relativeFile.parent?.let { File(metadataRoot, it) } ?: metadataRoot
        return File(parent, ".proot-meta-file.${relativeFile.name}")
    }

    fun fishAccessScript(): String = buildString {
        append("for path in")
        targets.forEach { append(" /").append(it) }
        append("; if test -x \"\$path\"; echo ACCESS_X_OK:\$path; else; echo ACCESS_X_DENIED:\$path; end; end")
    }

    /** Runs inside the same login Fish environment as the terminal; all results are read-only. */
    fun packageManagerScript(): String = """
        echo PACKAGE_PROBE_PATH=${'$'}PATH
        set -l selected (command -s fakeroot 2>/dev/null)
        echo PACKAGE_PROBE_FAKEROOT_SELECTED=${'$'}selected
        for path in $FAKEROOT_PREFIX $FAKEROOT_PREFIX/bin $FAKEROOT_PREFIX/bin/fakeroot $FAKEROOT_PREFIX/bin/faked $FAKEROOT_PREFIX/lib $FAKEROOT_PREFIX/lib/libfakeroot.so $FAKEROOT_PREFIX/lib/libfakeroot-0.so
            stat -L -c 'PACKAGE_PROBE_STAT=%A %a %u:%g %n' ${'$'}path 2>&1
            echo PACKAGE_PROBE_STAT_EXIT=${'$'}status
            if test -r ${'$'}path; echo PACKAGE_PROBE_READABLE=${'$'}path; else; echo PACKAGE_PROBE_UNREADABLE=${'$'}path; end
            if test -x ${'$'}path; echo PACKAGE_PROBE_EXECUTABLE=${'$'}path; else; echo PACKAGE_PROBE_NOT_EXECUTABLE=${'$'}path; end
            if test -L ${'$'}path; echo PACKAGE_PROBE_LINK=${'$'}path:(readlink ${'$'}path 2>&1); end
        end
        $FAKEROOT_PREFIX/bin/fakeroot -- /usr/bin/id -u 2>&1
        echo PACKAGE_PROBE_TCP_FAKEROOT_EXIT=${'$'}status
        /usr/bin/fakeroot -- /usr/bin/id -u 2>&1
        echo PACKAGE_PROBE_SYSTEM_FAKEROOT_EXIT=${'$'}status
        for host in mirror.archlinuxarm.org aur.archlinux.org
            getent ahostsv4 ${'$'}host 2>&1 | head -n 2
            echo PACKAGE_PROBE_DNS_${'$'}host=${'$'}pipestatus[1]
        end
        echo PACKAGE_PROBE_ALARM_TRUSTED_FILE=(string collect < /usr/share/pacman/keyrings/archlinuxarm-trusted)
    """.trimIndent()

    fun pacmanKeyringRepairScript(): String = """
        set -eu
        test ! -e /var/lib/pacman/db.lck || { echo 'Pacman database is locked; close package operations and retry.' >&2; exit 73; }
        test -r /usr/share/pacman/keyrings/archlinuxarm.gpg
        grep -qi '^$ALARM_SIGNING_FINGERPRINT:4:${'$'}' /usr/share/pacman/keyrings/archlinuxarm-trusted
        pacman-key --init
        pacman-key --populate archlinuxarm
        gpg --homedir /etc/pacman.d/gnupg --batch --export-ownertrust | grep -q '^$ALARM_SIGNING_FINGERPRINT:'
        gpg --homedir /etc/pacman.d/gnupg --batch --with-colons --list-keys '$ALARM_SIGNING_FINGERPRINT' | grep -q '^pub:'
        printf '%s\n' '$ALARM_SIGNING_FINGERPRINT' > /root/.tritonterm-pacman-keyring-ready.tmp
        mv -f /root/.tritonterm-pacman-keyring-ready.tmp /root/.tritonterm-pacman-keyring-ready
        echo 'TRITONTERM_PACMAN_KEYRING_REPAIRED fingerprint=$ALARM_SIGNING_FINGERPRINT'
    """.trimIndent()
}
