# Runs under the same `pr-cli login --user triton /usr/bin/fish -l -i -c`
# invocation used by TritonTerm. Feature failures are reported individually and
# never prevent the interactive shell from starting.
set failures 0
echo "CHECK_INFO PATH=$PATH"

function check_equals --argument-names name expected actual
    if test "$expected" = "$actual"
        echo CHECK_OK $name
    else
        echo "CHECK_FAILED $name expected=$expected actual=$actual"
        set -g failures (math $failures + 1)
    end
end

function check_executable --argument-names name path
    if test -x "$path"
        echo CHECK_OK $name
    else
        echo "CHECK_FAILED $name missing_or_not_executable=$path"
        set -g failures (math $failures + 1)
    end
end

function check_command_resolution --argument-names name
    set resolved (command -s $name 2>/dev/null)
    if test -n "$resolved"
        echo "CHECK_OK command_resolution_$name=$resolved"
    else
        echo "CHECK_FAILED command_resolution_$name executable_not_found_in_PATH"
        set -g failures (math $failures + 1)
    end
end

check_equals username triton "$USER"
check_equals home /home/triton "$HOME"
check_equals cwd "$HOME" "$PWD"
check_equals shell /usr/bin/fish "$SHELL"
check_executable fish /usr/bin/fish
check_executable id /usr/bin/id
check_executable mkdir /usr/bin/mkdir
check_executable uname /usr/bin/uname
check_executable sudo /usr/bin/sudo
check_executable system_fakeroot /usr/bin/fakeroot
check_executable pacman /usr/bin/pacman
check_executable paru /usr/bin/paru
check_executable rustup /usr/bin/rustup
check_executable starship /usr/bin/starship
check_command_resolution id
check_command_resolution mkdir
check_command_resolution uname
check_command_resolution sudo
check_command_resolution fakeroot
check_command_resolution fish
check_command_resolution starship
check_command_resolution rustup

if test -x /usr/bin/id
    check_equals uid 1000 (/usr/bin/id -u)
    check_equals gid 1000 (/usr/bin/id -g)
else
    echo CHECK_FAILED guest_identity id_unavailable
    set failures (math $failures + 1)
end

if set -q TRITONTERM_FISH_CONFIG_LOADED
    echo CHECK_OK fish_system_config
else
    echo CHECK_FAILED fish_system_config not_loaded
    set failures (math $failures + 1)
end

set greeting_output (fish_greeting)
if test -z "$greeting_output"
    echo CHECK_OK fish_greeting_suppressed
else
    echo "CHECK_FAILED fish_greeting_suppressed output=$greeting_output"
    set failures (math $failures + 1)
end

if command -q fakeroot
    check_equals fakeroot_path /opt/tritonterm/fakeroot-tcp/2.1.4/bin/fakeroot (command -s fakeroot)
    if test -r /opt/tritonterm/fakeroot-tcp/2.1.4/lib/libfakeroot.so
        echo CHECK_OK fakeroot_library
    else
        echo CHECK_FAILED fakeroot_library missing_or_unreadable
        set failures (math $failures + 1)
    end
    if fakeroot -- /usr/bin/id -u >/dev/null 2>&1
        echo CHECK_OK fakeroot_fake_uid
    else
        echo "CHECK_FAILED fakeroot_fake_uid exit=$status"
        set failures (math $failures + 1)
    end
else
    echo CHECK_FAILED fakeroot_path command_not_found
    set failures (math $failures + 1)
end

if command -q sudo
    if sudo -n /usr/bin/pacman -Q pacman >/dev/null 2>&1
        echo CHECK_OK sudo_pacman
    else
        echo "CHECK_FAILED sudo_pacman exit=$status"
        set failures (math $failures + 1)
    end
    if sudo -n /usr/bin/pacman -Dk >/dev/null 2>&1
        echo CHECK_OK pacman_database
    else
        echo "CHECK_FAILED pacman_database exit=$status"
        set failures (math $failures + 1)
    end
    if sudo -n /usr/bin/id -u >/dev/null 2>&1
        echo CHECK_FAILED sudo_unrestricted unexpectedly_allowed
        set failures (math $failures + 1)
    else
        echo CHECK_OK sudo_unrestricted_denied
    end
else
    echo CHECK_FAILED sudo_policy command_not_found
    set failures (math $failures + 1)
end

if abbr -q up
    echo CHECK_OK fish_abbreviation
else
    echo CHECK_FAILED fish_abbreviation up_missing
    set failures (math $failures + 1)
end

if functions -q fish_prompt; and functions fish_prompt | string match -q '*starship*'
    echo CHECK_OK fish_starship_prompt
else
    echo CHECK_FAILED fish_starship_prompt missing
    set failures (math $failures + 1)
end

echo "TRITONTERM_GUEST_CHECKS_COMPLETED failures=$failures"
