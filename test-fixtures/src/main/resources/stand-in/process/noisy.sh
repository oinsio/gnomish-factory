#!/bin/sh
# Writes $1 as a line $2 times to each stream: more than any OS pipe buffer (CaptureRunnerDrainSpec).
i=0
while [ $i -lt "$2" ]; do
    printf '%s\n' "$1"
    printf '%s\n' "$1" >&2
    i=$((i + 1))
done
