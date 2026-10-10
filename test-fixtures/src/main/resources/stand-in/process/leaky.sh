#!/bin/sh
# Leaks a holder of its stdout out of its own process tree (pid to $1), speaks once and stalls
# (ProcessSupervisorInterruptSpec, CaptureRunnerDrainSpec).
( sleep 30 & echo $! > "$1" )
echo started
sleep 600
