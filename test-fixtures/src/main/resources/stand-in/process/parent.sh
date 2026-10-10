#!/bin/sh
# Forks a TERM-ignoring child (pid to $1), waits for it, then stays alive reading stdin
# (ProcessSupervisorDescendantKillSpec).
sh -c 'trap "" TERM; sleep 600' &
echo $! > "$1"
wait
read line
