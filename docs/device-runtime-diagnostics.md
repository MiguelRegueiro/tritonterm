# Device runtime diagnostics

This build records setup events as newline-delimited JSON in the app's private
no-backup directory. Open **Logs** in the terminal header to inspect the log,
copy it, or export it through Android's document picker. The installer now runs
outside the terminal PTY as a single-flight Android process. Its stdout and
stderr are captured separately, with command arguments, working directory,
process/app PID information when available, elapsed time, timeout, and exit
code. Interactive shell output is not copied into diagnostics.

Events include download and signature-verification duration, plugin and cache
resolution, installer launch and process output/exit, extraction duration and
progress, cleanup/preservation outcome, rootfs validation, pacman-key
initialization and population exit codes, interactive shell launch/output, and
terminal process exit code. `pr-cli` writes installer/cache/extraction events
to the same JSONL file. Failures are shown in the setup panel with a prompt to
export Logs. Existing rootfs paths are rejected before install; the diagnostic
installer process preserves both the verified archive and any newly-created
partial rootfs after failure.

## Runtime probes

In **Logs**, tap **Run runtime probes**. It performs three separate checks:

1. Runs PRoot with `--rootfs=/` and Android's `/system/bin/sh` to print a fixed
   marker. This removes the empty guest root and bind setup from the first test.
2. Runs PRoot against a new empty temporary rootfs with explicit `/system` and
   `/apex` binds, then checks that `/system/bin/linker64` and its resolved
   target under `/apex` are executable and visible inside the guest. The log
   records the host linker target and `/apex` access flags. If `/apex` exists,
   the test attempts the explicit bind even when directory listing is denied;
   PRoot's bind or guest linker result records whether access actually works.
3. Runs the packaged `pr-cli --help` directly, outside PRoot and the terminal
   session, recording its output and exit code.

The PRoot commands only print fixed markers and take no user input. Their
scratch files live in unique app-cache directories that are removed after each
run. None of the probes invokes installation or accesses/modifies the Arch
rootfs, verified archive, plugin, or home directory. A PRoot pass validates the
bundled executable and child launch path on that phone; it does not prove Arch's
glibc binaries or package manager work.

The phone has passed the root PRoot, isolated `/system` plus `/apex`, and direct
`pr-cli --help` probes. These probes remain independent of installation and do
not touch the Arch rootfs or verified archive.

## Phone test and result collection

1. Install the diagnostic APK as an update over the current app. Do not
   uninstall TritonTerm, clear its storage, remove the rootfs, or delete the
   downloaded archive.
2. If TritonTerm opens an existing Arch shell, leave it intact and open
   **Logs**. Export diagnostics and report that the existing shell opened.
3. If no Arch shell exists, tap **Test launcher** once. This runs only the
   harmless `libpr-cli.so --help` command from Android's native library path; it
   does not install or modify the rootfs/archive. Confirm the app shows that the
   launcher passed. If it fails, stop and export Logs.
4. After that check passes, tap **Install Arch** once. Keep the app open and
   wait. Do not tap again while it says setup is running. Opening Logs is safe
   and does not restart setup. Setup output is still being captured if you open
   Logs during the run; use **Export** after the process finishes or reports a
   visible failure.
5. If the app reports an incomplete existing rootfs, do not start an install.
   The existing files have been preserved. Export the diagnostics for review.
6. Send the exported `.jsonl` file and state whether the harmless launcher test
   passed, whether you tapped **Install Arch**, the last visible status, and
   how long it stayed there. If Android's document picker creates a 0 B file,
   use **Copy** in Logs and paste the copied JSONL text directly into your
   reply instead.

The diagnostics may include filesystem paths and setup output. Review the file
before sharing it if you have added anything unusual to the app's setup config.
