#!/bin/sh
# Speaks, then leaves a holder of its stdout that has left the process group for a session of its
# own (setsid on Linux, the OS's perl on macOS — the forms the launch owner uses), ignores SIGPIPE
# and writes a line every 50 ms until it is killed; the holder writes its own pid to $1 once it is
# in its session, and the script exits 0 only then, so the group sweep cannot reach it (EscapedHolderSpec).
echo spoken
holder='trap "" PIPE; echo $$ > "$1.tmp" && mv "$1.tmp" "$1"; while :; do echo held; sleep 0.05; done'
if [ "$(uname -s)" = Darwin ]; then
    /usr/bin/perl -e 'use POSIX (); POSIX::setsid(); exec @ARGV or exit 127' /bin/sh -c "$holder" holder "$1" &
else
    setsid /bin/sh -c "$holder" holder "$1" &
fi
while [ ! -s "$1" ]; do sleep 0.01; done
exit 0
