#!/bin/sh
# Speaks, exits cleanly, and leaves a child holding its stdout open (pid to $1)
# (ProcessSupervisorInterruptSpec).
echo spoken
sleep 600 &
echo $! > "$1"
exit 0
