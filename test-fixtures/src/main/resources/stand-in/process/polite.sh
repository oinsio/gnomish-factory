#!/bin/sh
# Answers TERM by lingering half a second and exiting 143; forks a child whose own TERM handler
# leaves a term.marker in the working directory and keeps waiting on a grandchild that ignores
# TERM, and writes that child's pid to $1 (ProcessSupervisorGraceSpec). Nothing is forked after
# the TERM: a process a handler forked once its parent had died is reachable by no snapshot of
# the tree, and would outlive the spec (the group sweep's subject, not this fixture's).
trap 'sleep 0.5; exit 143' TERM
sh -c 'trap "printf term > term.marker" TERM; (trap "" TERM; exec sleep 600) & wait; wait' &
echo $! > "$1"
wait
