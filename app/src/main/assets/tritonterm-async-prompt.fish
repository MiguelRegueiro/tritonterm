# TritonTerm managed Starship adapter. Set TRITONTERM_ASYNC_PROMPT=0 to opt out.
# One timed process group renders a captured context; Fish keeps reading input.
if contains -- --worker $argv
    set -l dir $argv[2]
    set -l ticket $argv[3]
    set -l binary $argv[4]
    set -l verify $argv[5]
    set -l query_right $argv[6]
    set -l right $argv[7]
    set -l width $argv[8]
    set -l code $argv[9]
    set -l pipes $argv[10]
    set -l keymap $argv[11]
    set -l duration $argv[12]
    set -l jobs $argv[13]
    if test "$verify" = 1
        command $binary init fish --print-full-init | source
        or exit 1
        for side in left right
            set -l name fish_prompt
            test $side = right; and set name fish_right_prompt
            set -l actual (functions $name | string match -rv '^\s*#' | string collect)
            set -l expected
            read -z expected <$dir/expected.$side
            if test "$actual" != "$expected"
                printf '%s' "$ticket" >$dir/unsupported
                exit 1
            end
        end
    end
    if test "$query_right" = 1
        # Parse Starship's normalized TOML, not a possibly conditionally empty render.
        set -l config (command $binary print-config right_format 2>/dev/null)
        set right 1
        if test $status = 0; and string match -rq '^right_format = ""$' -- $config
            set right 0
        end
    end
    set -l args --terminal-width="$width" --status="$code" --pipestatus="$pipes" --keymap="$keymap" --cmd-duration="$duration" --jobs="$jobs"
    command $binary prompt $args >$dir/left
    or exit 1
    printf '' >$dir/right
    if test "$right" = 1
        command $binary prompt --right $args >$dir/right
        or exit 1
    end
    printf '%s\n%s\n' "$ticket" "$right" >$dir/ready
    exit 0
end
status is-interactive; or return
set -q __triton_prompt_loaded; and return
set -g __triton_prompt_loaded 1
set -g __triton_prompt_script (status filename)

function __triton_prompt_now
    set -l seconds rest
    read -l seconds rest 2>/dev/null </proc/uptime; or return 1
    string match -rq '^\d+(\.\d+)?$' -- "$seconds"; or return 1
    math --scale=0 "$seconds * 1000"
end

function __triton_prompt_config
    set -l file "$HOME/.config/starship.toml"
    set -q XDG_CONFIG_HOME; and set file "$XDG_CONFIG_HOME/starship.toml"
    set -q STARSHIP_CONFIG; and set file "$STARSHIP_CONFIG"
    set -l text __missing__
    if test -r "$file"
        read -z text <$file
    end
    string escape -- "$file" "$text"
end

function __triton_prompt_status -a value
    return $value
end

# Reconstruct status/pipestatus immediately before calling the original function.
function __triton_prompt_sync -a side code pipes
    set -e argv[1..3]
    set -l commands
    for value in (string split ' ' -- "$pipes")
        set -a commands "__triton_prompt_status $value"
    end
    set -l pipeline (string join ' | ' -- $commands)
    set -l last (string split ' ' -- "$pipes")[-1]
    test "$code" != "$last"; and set pipeline "not $pipeline"
    set -l call (string join ' ' -- (string escape -- __triton_prompt_original_$side $argv))
    eval "$pipeline; $call"
end

function __triton_prompt_stop
    if set -q __triton_prompt_pid
        # This stable parent-owned file avoids racing a disappearing pid/stat.
        set -l children
        if test -r /proc/self/task/$fish_pid/children
            read -l children </proc/self/task/$fish_pid/children
            if contains -- "$__triton_prompt_pid" (string split ' ' -- "$children")
                set -l target $__triton_prompt_pid
                command kill -TERM -- -$target 2>/dev/null
                command kill -KILL -- -$target 2>/dev/null
            end
        end
    end
end

function __triton_prompt_postexec --on-event fish_postexec
    set -q __triton_prompt_generation; or return
    # A postexec event can contain a whole multiline paste. Test its contents
    # directly: command substitution would split it into multiple test arguments.
    if string match -rq '\S' -- "$argv"
        set -g __triton_prompt_generation (math $__triton_prompt_generation + 1)
    end
end

function __triton_prompt_finish_impl
    set -e __triton_prompt_pid
    if test "$__triton_prompt_disabled" = 1; or test "$TRITONTERM_ASYNC_PROMPT" = 0
        return
    end
    set -l ready
    if test -r $__triton_prompt_dir/ready
        set ready (string split \n < $__triton_prompt_dir/ready)
    end
    if test (count $ready) != 2; or test "$ready[1]" != "$__triton_prompt_ticket"
        set -l unsupported
        test -r $__triton_prompt_dir/unsupported; and read -z unsupported <$__triton_prompt_dir/unsupported
        if test "$unsupported" = "$__triton_prompt_ticket"
            set -g __triton_prompt_disabled 1
        else
            # A failed/timed-out renderer must not block input via a sync retry.
            set -g __triton_prompt_retry_after (math (__triton_prompt_now) + 2000)
        end
    else
        set -g __triton_prompt_retry_after 0
        set -g __triton_prompt_verified 1
        # Publish only to the exact directory and command generation requested.
        if test "$PWD" = "$__triton_prompt_worker_pwd"; and test "$__triton_prompt_generation" = "$__triton_prompt_worker_generation"
            read -zg __triton_prompt_left <$__triton_prompt_dir/left
            read -zg __triton_prompt_right <$__triton_prompt_dir/right
            set -g __triton_prompt_cache_key "$__triton_prompt_worker_key"
            set -g __triton_prompt_completed (__triton_prompt_now)
            set -g __triton_prompt_right_config "$__triton_prompt_worker_config"
            set -g __triton_prompt_right_enabled "$ready[2]"
        end
    end
    # Fish queues a repaint without editing or executing the command line.
    commandline -f repaint
end

function __triton_prompt_draw -a code pipes
    __triton_prompt_children
    if test "$__triton_prompt_disabled" = 1
        __triton_prompt_sync left "$code" "$pipes"
        return
    end
    set -l now (__triton_prompt_now)
    if test $status != 0
        set -g __triton_prompt_disabled 1
        __triton_prompt_sync left "$code" "$pipes"
        return
    end
    set -l keymap insert
    if contains -- "$fish_key_bindings" fish_hybrid_key_bindings fish_vi_key_bindings fish_helix_key_bindings
        set keymap "$fish_bind_mode"
    end
    set -l groups (jobs -g 2>/dev/null)
    if test "$__starship_fish_use_job_groups" = false
        set groups (jobs -p 2>/dev/null)
    end
    set -l user_jobs
    for group in $groups
        test "$group" = "$__triton_prompt_pid"; or set -a user_jobs $group
    end
    set -l duration "$CMD_DURATION$cmd_duration"
    set -l config (__triton_prompt_config)
    set -l fields "$PWD" "$__triton_prompt_generation" "$code" "$pipes" "$duration" "$COLUMNS" "$keymap" (count $user_jobs) "$config"
    for name in PATH RUSTUP_HOME RUSTUP_TOOLCHAIN CARGO_HOME VIRTUAL_ENV CONDA_DEFAULT_ENV LANG TZ STARSHIP_CONFIG STARSHIP_SHELL
        set -a fields "$name" (count $$name) (string escape -- $$name)
    end
    set -l key (string join \n -- (string escape -- $fields))
    set -g __triton_prompt_key "$key"
    set -l expired 1
    if test -n "$__triton_prompt_completed"
        test (math "$now - $__triton_prompt_completed") -lt 1000; and set expired 0
    end
    if not set -q __triton_prompt_pid; and test "$now" -ge "$__triton_prompt_retry_after"; and begin; test "$key" != "$__triton_prompt_cache_key"; or test $expired = 1; end
        set -g __triton_prompt_ticket (math $__triton_prompt_ticket + 1)
        set -g __triton_prompt_worker_key "$key"
        set -g __triton_prompt_worker_pwd "$PWD"
        set -g __triton_prompt_worker_generation "$__triton_prompt_generation"
        set -g __triton_prompt_worker_config "$config"
        set -l query 0
        test "$config" != "$__triton_prompt_right_config"; and set query 1
        command rm -f -- $__triton_prompt_dir/ready $__triton_prompt_dir/unsupported
        set -l last_background $last_pid
        command $__triton_prompt_timeout --kill-after=1s 3s $__triton_prompt_fish --no-config $__triton_prompt_script --worker \
            $__triton_prompt_dir $__triton_prompt_ticket $__triton_prompt_binary \
            (math "1 - $__triton_prompt_verified") $query $__triton_prompt_right_enabled \
            "$COLUMNS" "$code" "$pipes" "$keymap" "$duration" (count $user_jobs) \
            </dev/null >$__triton_prompt_dir/worker.stdout 2>$__triton_prompt_dir/worker.stderr &
        set -g __triton_prompt_pid $last_pid
        set -g last_pid $last_background
        disown $__triton_prompt_pid 2>/dev/null
        __triton_prompt_children
    end
    if test "$key" = "$__triton_prompt_cache_key"
        printf '%s' "$__triton_prompt_left"
    else
        # A different directory/status never displays an old context as current.
        test "$code" = 0; or printf '[%s] ' "$code"
        printf '%s … ' (string escape -- "$PWD")
    end
end

# Natural child-exit notifications also work for disowned jobs. No private
# signal is sent, and the worker never appears among the user's jobs.
function __triton_prompt_children --on-signal CHLD
    set -q __triton_prompt_pid; or return
    # A valid ready record means all rendering finished, even if timeout is
    # still reaping the worker. Otherwise wait until the direct child is gone.
    set -l ready
    if test -r $__triton_prompt_dir/ready
        set ready (string split \n < $__triton_prompt_dir/ready)
    end
    if test (count $ready) = 2; and test "$ready[1]" = "$__triton_prompt_ticket"
        __triton_prompt_finish_impl
    else
        set -l unsupported
        test -r $__triton_prompt_dir/unsupported; and read -z unsupported <$__triton_prompt_dir/unsupported
        if test "$unsupported" = "$__triton_prompt_ticket"; or not test -d /proc/$__triton_prompt_pid
            __triton_prompt_finish_impl
        end
    end
end

function __triton_prompt_setup --on-event fish_prompt
    functions -e __triton_prompt_setup
    test "$TRITONTERM_ASYNC_PROMPT" = 0; and return
    functions -q fish_prompt; and functions -q fish_right_prompt; or return
    set -l left (functions fish_prompt | string match -rv '^\s*#' | string collect)
    set -l right (functions fish_right_prompt | string match -rv '^\s*#' | string collect)
    string match -q '*starship prompt*' -- "$left"; or return
    string match -q '*starship prompt --right*' -- "$right"; or return
    command -q timeout; or return
    set -g __triton_prompt_timeout (command -s timeout)
    set -g __triton_prompt_fish (status fish-path)
    __triton_prompt_now >/dev/null; or return
    set -g __triton_prompt_binary (command -s starship)
    set -g __triton_prompt_dir (command mktemp -d /tmp/tritonterm-prompt.XXXXXXXX)
    test -d "$__triton_prompt_dir"; or return
    printf '%s' "$left" >$__triton_prompt_dir/expected.left
    printf '%s' "$right" >$__triton_prompt_dir/expected.right
    set -g __triton_prompt_generation 0
    set -g __triton_prompt_ticket 0
    set -g __triton_prompt_retry_after 0
    set -g __triton_prompt_verified 0
    set -g __triton_prompt_disabled 0
    set -g __triton_prompt_cache_key ''
    set -g __triton_prompt_completed ''
    set -g __triton_prompt_right_config ''
    set -g __triton_prompt_right_enabled 1
    functions -c fish_prompt __triton_prompt_original_left
    functions -c fish_right_prompt __triton_prompt_original_right
    function fish_prompt --description 'TritonTerm bounded starship prompt'
        set -l pipes $pipestatus
        set -l code $status
        set pipes (string join ' ' -- $pipes)
        if test "$TRITONTERM_ASYNC_PROMPT" = 0; or test "$__triton_prompt_disabled" = 1; or contains -- --final-rendering $argv; or test "$TRANSIENT" = 1
            __triton_prompt_stop
            __triton_prompt_sync left "$code" "$pipes" $argv
        else
            __triton_prompt_draw "$code" "$pipes"
        end
    end
    function fish_right_prompt --description 'TritonTerm bounded starship right prompt'
        set -l pipes $pipestatus
        set -l code $status
        set pipes (string join ' ' -- $pipes)
        if test "$TRITONTERM_ASYNC_PROMPT" = 0; or test "$__triton_prompt_disabled" = 1; or contains -- --final-rendering $argv; or test "$RIGHT_TRANSIENT" = 1
            __triton_prompt_sync right "$code" "$pipes" $argv
        else if test "$__triton_prompt_key" = "$__triton_prompt_cache_key"
            printf '%s' "$__triton_prompt_right"
        end
    end
end

function __triton_prompt_exit --on-event fish_exit
    if set -q __triton_prompt_dir
        set -g __triton_prompt_disabled 1
        functions -e __triton_prompt_children
        __triton_prompt_stop
        command rm -rf -- $__triton_prompt_dir
    end
end
