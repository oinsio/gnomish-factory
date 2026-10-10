#!/bin/sh
# Answers TERM by forking a fresh TERM-ignoring child, whose pid it writes to $1, and carrying on
# (ProcessSupervisorTreeKillSpec: a child forked while the tree is being killed).
trap 'sh -c "trap \"\" TERM; sleep 600" & echo $! > "$1"; wait' TERM
sleep 600 &
wait
