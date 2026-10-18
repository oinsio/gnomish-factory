#!/bin/sh
# Exits cleanly and leaves a child holding only its stdin open (pid to $1): the child's stdout and
# stderr go to /dev/null, so no reader waits on it, and its stdin is redirected explicitly, since a
# shell gives a background job /dev/null otherwise (StdinSourceSpec).
sleep 600 <&0 >/dev/null 2>&1 &
echo $! > "$1"
exit 0
