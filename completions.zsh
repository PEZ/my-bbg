_bbg_tasks() {
    local matches=(`bbg tasks |tail -n +3 |cut -f1 -d ' '`)
    compadd -a matches
}
compdef _bbg_tasks bbg
