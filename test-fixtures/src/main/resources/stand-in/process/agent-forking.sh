#!/bin/sh
# An agent CLI that forks a child of its own (pid to $1) and then outlives any round budget
# (HostExecHandleTreeKillSpec).
sleep 600 &
echo $! > "$1"
wait
