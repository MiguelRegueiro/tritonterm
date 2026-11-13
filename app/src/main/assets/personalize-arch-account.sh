#!/usr/bin/env bash
# Runs as root inside the prebuilt image. Safe to repeat after process interruption.
set -Eeuo pipefail

readonly TEMPLATE_USER='dev'
readonly TEMPLATE_UID='1000'
readonly STATE_DIR='/etc/tritonterm'
readonly STATE_FILE="${STATE_DIR}/account-selection"
readonly SELECTED_FILE="${STATE_DIR}/selected-user"
readonly SUDOERS_FILE='/etc/sudoers.d/10-tritonterm-pacman'
readonly RUN_LOG='/var/cache/tritonterm/developer-setup.log'
stage='account_validation'

fail() { printf '[Arch setup] FAILED at %s (exit 1): %s\n' "$stage" "$*" >&2; exit 1; }
on_error() { local code=$?; printf '[Arch setup] FAILED at %s (exit %s): command: %s\n' "$stage" "$code" "$BASH_COMMAND" >&2; exit "$code"; }
trap on_error ERR
[[ "$(id -u)" == 0 ]] || fail 'must run as root inside the Arch guest'
[[ $# == 1 ]] || fail 'expected exactly one requested username'
requested="$1"
[[ "$requested" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || fail 'username syntax is invalid'
[[ "$requested" != *- ]] || fail 'username cannot end with a hyphen'
case "$requested" in
    root|daemon|bin|sys|sync|games|man|lp|mail|news|uucp|proxy|www-data|backup|list|irc|nobody|systemd-network|systemd-resolve)
        fail 'username is reserved'
        ;;
esac

install -d -m 0755 "$STATE_DIR"
install -d -m 0755 "$(dirname "$RUN_LOG")"
exec 3>&2
exec > >(tee -a "$RUN_LOG") 2> >(sed 's/^/[stderr] /' | tee -a "$RUN_LOG" >&3)
stage='account_rename'
if [[ -f "$STATE_FILE" ]]; then
    recorded="$(sed -n 's/^username=//p' "$STATE_FILE" | head -n 1)"
    [[ "$recorded" == "$requested" ]] || fail 'an interrupted rename is already committed to a different username'
else
    if [[ "$requested" != "$TEMPLATE_USER" ]] && getent passwd "$requested" >/dev/null; then
        fail 'the requested username already exists in the image'
    fi
    if [[ "$requested" != "$TEMPLATE_USER" ]] && getent group "$requested" >/dev/null; then
        fail 'the requested name is already used by a system group'
    fi
    other_uid_users="$(awk -F: -v uid="$TEMPLATE_UID" '$3 == uid && $1 != "dev" { print $1 }' /etc/passwd)"
    [[ -z "$other_uid_users" ]] || fail "unexpected account already uses template UID ${TEMPLATE_UID}: ${other_uid_users}"
    temp_state="${STATE_FILE}.new.$$"
    printf 'username=%s\nphase=started\n' "$requested" > "$temp_state"
    chmod 0600 "$temp_state"
    mv -f "$temp_state" "$STATE_FILE"
fi

template_line="$(awk -F: -v uid="$TEMPLATE_UID" '$3 == uid { print; exit }' /etc/passwd)"
[[ -n "$template_line" ]] || fail "no template account owns UID ${TEMPLATE_UID}"
IFS=: read -r current_user _ uid gid _ current_home current_shell <<< "$template_line"
[[ "$current_user" == "$TEMPLATE_USER" || "$current_user" == "$requested" ]] ||
    fail "UID ${TEMPLATE_UID} belongs to unexpected account ${current_user}"
[[ "$uid" == "$TEMPLATE_UID" && "$gid" == "$TEMPLATE_UID" ]] || fail 'template UID/GID differs from the image contract'
[[ "$current_home" == /home/dev || "$current_home" == "/home/${requested}" ]] ||
    fail "unexpected template home path: ${current_home}"

if [[ "$current_user" == "$TEMPLATE_USER" && "$requested" != "$TEMPLATE_USER" ]]; then
    printf '[Arch setup][account_rename] Renaming template login dev to %s.\n' "$requested"
    usermod --login "$requested" dev
fi

group_name="$(getent group "$TEMPLATE_UID" | cut -d: -f1)"
[[ -n "$group_name" ]] || fail "primary group ${TEMPLATE_UID} is missing"
if [[ "$group_name" == "$TEMPLATE_USER" && "$requested" != "$TEMPLATE_USER" ]]; then
    if getent group "$requested" >/dev/null; then fail "group ${requested} already exists"; fi
    groupmod --new-name "$requested" "$TEMPLATE_USER"
elif [[ "$group_name" != "$requested" ]]; then
    fail "primary group ${TEMPLATE_UID} has unexpected name ${group_name}"
fi

current_home="$(getent passwd "$requested" | cut -d: -f6)"
target_home="/home/${requested}"
if [[ "$current_home" != "$target_home" ]]; then
    if [[ -e "$target_home" && "$target_home" != "$current_home" ]]; then
        [[ -d "$target_home" && -z "$(find "$target_home" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
            fail "target home already exists and is not empty: ${target_home}"
        rmdir "$target_home"
    fi
    usermod --home "$target_home" --move-home "$requested"
fi
stage='account_validation'
usermod --shell /usr/bin/fish --append --groups wheel "$requested"

final_line="$(getent passwd "$requested")"
IFS=: read -r final_name _ final_uid final_gid _ final_home final_shell <<< "$final_line"
[[ "$final_name" == "$requested" && "$final_uid" == "$TEMPLATE_UID" && "$final_gid" == "$TEMPLATE_UID" &&
   "$final_home" == "$target_home" && "$final_shell" == /usr/bin/fish ]] || fail 'account fields did not match the requested configuration'
id -nG "$requested" | tr ' ' '\n' | grep -qx wheel || fail 'user is not in the wheel group'
[[ -d "$target_home/.rustup" && -d "$target_home/.cargo" ]] || fail 'Rustup/Cargo home was not preserved during rename'

stage='sudo_policy'
sudoers_tmp="${SUDOERS_FILE}.new.$$"
printf '%s ALL=(root) NOPASSWD: /usr/bin/pacman\n' "$requested" > "$sudoers_tmp"
chmod 0440 "$sudoers_tmp"
if ! visudo -cf "$sudoers_tmp" >/dev/null; then
    rm -f "$sudoers_tmp"
    fail 'personalized Pacman sudo policy failed visudo validation'
fi
mv -f "$sudoers_tmp" "$SUDOERS_FILE"
visudo -cf /etc/sudoers >/dev/null || fail 'complete sudo policy failed visudo validation'

# Fish's universal fish_user_paths can retain absolute paths from the template
# account. Rebuild it from the renamed account's $HOME before the first shell.
/usr/bin/su -l "$requested" -s /usr/bin/fish -c 'set --universal --erase fish_user_paths; or true'
if [[ "$requested" != "$TEMPLATE_USER" ]]; then
    /usr/bin/su -l "$requested" -s /usr/bin/fish -c \
        'contains -- "$HOME/.local/bin" $PATH; and not contains -- /home/dev/.local/bin $PATH' ||
        fail 'Fish PATH still contains a template-home path after account rename'
fi

temp_selected="${SELECTED_FILE}.new.$$"
printf '%s\n' "$requested" > "$temp_selected"
chmod 0644 "$temp_selected"
mv -f "$temp_selected" "$SELECTED_FILE"
rm -f "$STATE_FILE"
printf 'TRITONTERM_ACCOUNT_READY username=%s uid=%s gid=%s home=%s shell=%s\n' \
    "$requested" "$final_uid" "$final_gid" "$final_home" "$final_shell"
