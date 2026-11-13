# Arch development account and package administration

TritonTerm's signed Arch Linux ARM development image uses UID/GID 1000 for its
template account. During installation, the user-selected Linux name replaces the
template name and home while retaining those numeric IDs. The selected name is
saved in `/etc/tritonterm/selected-user`; the terminal starts the account's Fish
login shell in its home directory.

## PRoot identity and storage metadata

Android app-private storage cannot assign arbitrary Unix ownership to extracted
files or retain setuid bits on host inodes. The installer records each archive
entry's mode, UID, and GID in hidden `.proot-meta-file.*` records. The bundled
PRoot build enables its USERLAND fake-ID extension. Only an image carrying
`/etc/tritonterm/proot-userland-metadata-v1` uses its passwd identity; other
distributions keep their existing fake-root sessions. Generic provisioning
commands explicitly run as guest root.

This is guest-side emulation, not a security boundary between Linux users. All
guest programs still run inside the TritonTerm Android app process and remain
confined by Android's app sandbox. Guest root and `sudo` do not grant device root.

## Package administration

Arch's system fakeroot uses System V IPC, which is unavailable in this PRoot
environment. The image carries upstream fakeroot 2.1.4 built with
`--with-ipc=tcp` under `/opt/tritonterm/fakeroot-tcp/2.1.4`. The versioned
wrapper, `faked`, and preload libraries stay together. Fish and login PATH setup
select this wrapper for ordinary `fakeroot` and `makepkg`; pacman's managed
`/usr/bin/fakeroot` remains untouched.

The image keeps Arch's normal sudo package and its checks. Its setuid bit and
root-owned metadata are supplied through PRoot metadata records, including
both `stat` and `statx` results. PRoot emulates the setuid transition inside the
guest; it leaves the Android process UID unchanged. `/etc/sudoers.d/10-tritonterm-pacman`
grants the selected account passwordless execution of `/usr/bin/pacman`; this
is the path makepkg and Paru use for dependency installation and package
transactions. Package installation necessarily runs package hooks as guest
root. AUR recipes are untrusted code, so users should inspect them before
building. This administrative workflow operates inside the app-private guest,
not as a security boundary against code running as the Android app owner; guest
root does not grant Android device root.

After account personalization, setup runs a guest-side acceptance check for
UID/GID 1000, `$HOME`, `$PWD`, Fish, Starship, the selected TCP fakeroot library,
and `sudo -n pacman -Q pacman`. The native AArch64 image build separately checks
the account, a real Rust compile/run, fake ownership, archive ownership/modes,
makepkg, and sudo-authorized package install. Android/PRoot behavior still needs
device validation; native Linux chroot checks cannot prove Android extraction
metadata or PRoot syscall behavior.
