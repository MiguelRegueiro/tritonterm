# Run as triton in a login Fish. This measures the configured prompt in the
# home directory both idle and while the preloaded Cargo cache is being walked.
function measure_prompt --argument-names label
    set started (date +%s%3N)
    starship prompt >/dev/null; or return 1
    set finished (date +%s%3N)
    echo "STARSHIP_PROMPT_"$label"_MS="(math $finished - $started)
end

for iteration in 1 2 3
    measure_prompt idle_$iteration; or exit 1
end

if test -d "$HOME/.cargo/registry/src"
    command find "$HOME/.cargo/registry/src" -type f -print0 >/dev/null 2>&1 &
    set scanner_pid $last_pid
    for iteration in 1 2 3
        measure_prompt filesystem_load_$iteration; or exit 1
    end
    wait $scanner_pid
end
