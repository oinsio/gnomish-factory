#!/bin/sh
# Leaves a child holding its stdout open (pid to $1), speaks, and exits cleanly once its own stdin
# ends — the cue, so a reader can be parked on the pipe before the exit (DrainCloseReleaseSpec).
sleep 600 &
echo $! > "$1"
echo spoken
read cue
exit 0
