#!/bin/sh
# Ignores TERM and forks a child that ignores it too, writing the child's pid to $1
# (ProcessSupervisorTreeKillSpec).
trap '' TERM
sh -c 'trap "" TERM; sleep 600' &
echo $! > "$1"
wait
