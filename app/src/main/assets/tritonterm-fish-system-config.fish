# System-wide defaults guarantee the same command path under pr-cli login.
set -gx PATH /opt/tritonterm/fakeroot-tcp/2.1.4/bin "$HOME/.cargo/bin" "$HOME/.local/bin" /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin
set -gx SYSTEMD_OFFLINE 1
set -gx STARSHIP_CONFIG "$HOME/.config/starship.toml"
set -g TRITONTERM_FISH_CONFIG_LOADED 1

function fish_greeting
end

if status is-interactive
    abbr --add up 'paru -Syu'
    if command -q starship
        starship init fish | source
    end
end
