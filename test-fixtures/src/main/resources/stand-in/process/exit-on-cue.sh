#!/bin/sh
# Leaves a child holding its stdout open (pid to $1), speaks, and lives until its own stdin ends —
# the cue — then exits cleanly; StreamDrainCloseReleaseSpec closes the round's drain while it lives.
sleep 600 &
echo $! > "$1"
echo spoken
read cue
exit 0
