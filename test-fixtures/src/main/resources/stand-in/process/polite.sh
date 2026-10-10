#!/bin/sh
# Answers TERM by lingering half a second and exiting 143; forks a child whose own TERM handler
# leaves a term.marker in the working directory, and writes that child's pid to $1
# (ProcessSupervisorGraceSpec).
trap 'sleep 0.5; exit 143' TERM
sh -c 'trap "printf term > term.marker; sleep 600" TERM; sleep 600 & wait' &
echo $! > "$1"
wait
