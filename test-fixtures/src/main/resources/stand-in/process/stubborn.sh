#!/bin/sh
# Ignores TERM and sleeps in a loop, so only a forcible kill of the process itself ends it
# (ProcessSupervisorInterruptSpec).
trap '' TERM
while :; do sleep 1; done
