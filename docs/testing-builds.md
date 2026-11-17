# Local testing APKs

Run the normal development command:

```sh
./scripts/build-development-apk.sh
```

No GitHub credentials, password entry, Actions run or artifact upload is needed.
The first build creates a local development signing key at
`~/.config/tritonterm/development.jks`. Later builds reuse it and automatically
increase the experimental build number and Android version code. The signed
APK is written to `dist/tritonterm-<version>-exp.<number>.apk`.

The local key differs from the GitHub release key. Back up your files before
uninstalling the release-signed app once to install your first local APK:
uninstalling removes the private Arch environment and files. Subsequent local
APKs update that local installation normally and preserve its data. Official
release APKs cannot update a local-key installation either.

Keep a private backup of `development.jks`; losing or replacing it prevents
future updates to apps signed with it. Its development-only password is
`android` and alias is `tritonterm-local`; the private key file is what must be
protected. The file is created with mode 600. It lives outside the repository
and `/tmp`. `$XDG_CONFIG_HOME/tritonterm` overrides the configuration directory.

The Arch image stays pinned to `2026.11.15.1-rc.56` by default. You can override
pins or signing credentials through environment variables, or a private trusted
shell file at `~/.config/tritonterm/development.env` (mode 600, owned by you,
not a symlink). `TRITONTERM_DEVELOPMENT_CONFIG` overrides that file location.
Explicit release signing credentials take precedence over automatic local-key
creation. Local build counter reservations survive failed builds as well.

The revised Android APK workflow is for stable `v*` tags only, with no testing
release or Actions artifact upload. The cleanup preserved only `v1.0.0` and
left the separate Arch image repository unchanged. Releases and tags are
repository-wide; a testing branch alone cannot hide prereleases.
