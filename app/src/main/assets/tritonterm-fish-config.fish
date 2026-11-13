function fish_greeting
end

if status is-interactive
    abbr --add up 'paru -Syu'
    if command -q starship
        starship init fish | source
    end
end
