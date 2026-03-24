_bbg() {
    if (( CURRENT == 2 )); then
        local tasks=(`bbg tasks | tail -n +3 | cut -f1 -d ' '`)
        compadd -a tasks
    else
        local task="${words[2]}"
        local opts=(`bbg -task-options "$task"`)
        if (( ${#opts} )); then
            compadd -a opts
        fi
    fi
}
compdef _bbg bbg
