#!/usr/bin/env fish
# Portable phone collection: Fish and the existing packages only; no Python install.
set -lx LANG C.UTF-8
set -lx LC_ALL C.UTF-8
set -lx TRITONTERM_CNG_PROFILE off
set -l report_root ~/.cache/tritonterm
mkdir -p $report_root; or exit 1
set -g profile_dir (mktemp -d $report_root/runtime-profile.XXXXXX)
or exit 1
chmod 700 $profile_dir
printf '%s\n' 'add_newline = false' 'format = "$character"' >$profile_dir/minimal.toml
printf '%s\n' 'add_newline = false' 'format = "$time"' '[time]' 'disabled = false' 'time_format = "%H:%M"' 'format = "$time"' >$profile_dir/clock.toml
printf 'Collecting into %s\n' $profile_dir
set -g profile_summary $profile_dir/summary.txt
printf '%s\n' 'BEGIN TRITONTERM PROFILE' >$profile_summary
printf '%s\n' 'Times below are external wall milliseconds; rows are three runs.' 'Metric pairs in the sample JSON are [count,nanoseconds]. Timers overlap: do not add them.' 'Scope begins after exec argument snapshot; entry is ELF/interpreter entry, not main.' 'Dispatch counters cover intercepted calls, not every native kernel syscall.' >>$profile_summary

function profile_workload --argument-names label directory config executable
    set -l arguments $argv[5..-1]
    set -l original $PWD
    cd $directory; or return 1
    set -l off
    set -l count
    set -l timing
    set -l example
    set -l reference
    printf 'Measuring %s...\n' $label
    for repeat in 1 2 3
        set -l modes off count timing
        if test $repeat = 2
            set modes count timing off
        else if test $repeat = 3
            set modes timing off count
        end
        for mode in $modes
            set -l log $profile_dir/$label-$repeat-$mode.stderr
            set -l out $profile_dir/$label-$repeat-$mode.stdout
            # Fish's time keyword writes to the shell's own stderr, not a
            # command/block redirection. A clean child shell captures it while
            # its timer measures only the target command, not shell startup.
            fish --no-config -c '
                set -lx TRITONTERM_CNG_PROFILE $argv[1]
                set -lx STARSHIP_CONFIG $argv[2]
                time $argv[3] $argv[4..-1]
            ' $mode $config $executable $arguments >$out 2>$log
            set -l result $status
            if test $result != 0
                printf 'Command failed: %s (%s); see %s\n' $label $result $log >&2
                cd $original
                return 1
            end
            set -l clean (string trim -- (string replace -ar '\x1b\[[0-?]*[ -/]*[@-~]' '' -- (cat $out)))
            if test $label = starship-minimal; and test "$clean" != '❯'
                printf 'Minimal prompt config was not applied; rejecting %s\n' $log >&2
                cd $original
                return 1
            end
            if test $label = starship-clock; and not string match -rq '^\d\d:\d\d$' -- "$clean"
                printf 'Clock config was not applied; rejecting %s\n' $log >&2
                cd $original
                return 1
            end
            if test $label = elio-version; and not string match -rq '^elio ' -- "$clean"
                printf 'Unexpected Elio version output: %s\n' $out >&2
                cd $original
                return 1
            end
            set -l normalized (string replace -ar '\d\d:\d\d' '<TIME>' -- "$clean")
            if test $mode = off; and test (count $reference) = 0
                set reference "$normalized"
            else if test "$normalized" != "$reference"
                printf 'Command/prompt output changed across modes; rejecting %s\n' $log >&2
                cd $original
                return 1
            end
            set -l lines (cat $log)
            set -l clock (string match -r 'Executed in\s+([0-9.]+)\s+(millis|secs|micros)' -- $lines)
            if test (count $clock) != 3
                printf 'Cannot parse Fish wall time: %s\n' $log >&2
                cd $original
                return 1
            end
            set -l ms $clock[2]
            switch $clock[3]
                case secs
                    set ms (math "$ms * 1000")
                case micros
                    set ms (math "$ms / 1000")
            end
            set -a $mode $ms
            set -l records (string match -r '^TRITONTERM_CNG_PROFILE .*' -- $lines)
            set -l found_exit 0
            for record in $records
                if not string match -rq -- '"v":1,' $record
                    printf 'Unexpected profile schema: %s\n' $log >&2
                    cd $original
                    return 1
                end
                if not string match -rq -- '"truncated":0}' $record
                    printf 'Incomplete profile: %s\n' $log >&2
                    cd $original
                    return 1
                end
                if not string match -rq -- "\"mode\":\"$mode\"" $record
                    printf 'Unexpected profile mode: %s\n' $log >&2
                    cd $original
                    return 1
                end
                if string match -rq -- '"reason":"exit"' $record
                    set found_exit 1
                    if test $mode = timing; and test $repeat = 1
                        set -a example $record
                    end
                end
            end
            if test $mode = off; and test (count $records) != 0
                printf 'Profiling off unexpectedly emitted a report.\n' >&2
                cd $original
                return 1
            end
            if test $mode != off; and test $found_exit != 1
                printf 'No chroot-ng exit report: select arch_runtime chroot-ng and reopen the updated app.\n' >&2
                cd $original
                return 1
            end
        end
    end
    printf '\n%s (%s)\n' $label $executable >>$profile_summary
    printf 'off_ms: %s\n' (string join ' ' $off) >>$profile_summary
    printf 'count_ms: %s\n' (string join ' ' $count) >>$profile_summary
    printf 'timing_ms: %s\n' (string join ' ' $timing) >>$profile_summary
    printf '%s\n' $example >>$profile_summary
    cd $original
end

set -l elio (command -s elio)
set -l starship (command -s starship)
if test -z "$elio"; or test -z "$starship"
    printf 'Installed elio and starship are required.\n' >&2
    exit 1
end
set -l config ~/.config/starship.toml
if set -q STARSHIP_CONFIG
    set config $STARSHIP_CONFIG
end
if not test -f $config
    printf 'Starship config not found: %s\n' $config >&2
    exit 1
end
set config (path resolve -- $config)
profile_workload elio-version $HOME $config $elio --version; or exit 1
profile_workload starship-minimal $HOME $profile_dir/minimal.toml $starship prompt; or exit 1
profile_workload starship-clock $HOME $profile_dir/clock.toml $starship prompt; or exit 1
profile_workload starship-home $HOME $config $starship prompt; or exit 1
if test -d ~/elio
    profile_workload starship-repo ~/elio $config $starship prompt; or exit 1
end
printf '\nEND TRITONTERM PROFILE\n' >>$profile_summary
cat $profile_summary
printf '\nDetailed reports: %s\n' $profile_dir
