# Arch development account

TritonTerm's prepared Arch image has one fixed, uncredentialed interactive
account: `triton`, UID/GID 1000, home `/home/triton`, login shell Fish. Keeping
the account fixed avoids first-boot account renames that can leave Rustup,
Fish, sudo policy, and file ownership inconsistent. Root remains available for
recovery through the app's PRoot login flow; the ordinary terminal opens as
`triton`.

The account password is locked. A narrow sudoers rule permits only
`/usr/bin/pacman`, which is needed by normal Paru and makepkg install flows.
PRoot emulates guest ownership and setuid metadata inside app-private storage;
guest root is not Android device root and is not a security boundary from
programs running as the TritonTerm app owner. AUR build scripts run as the
unprivileged `triton` user and should be reviewed before building.

Rustup, Cargo, Fish, Starship, TCP fakeroot, and Paru are prepared in the image.
No first-shell account rename or toolchain build is required.
