# ARGV_RECORDER_REAL names a file holding the real CLI's path; ARGV_RECORDER_CAPTURES is a directory.
real=$(cat "$ARGV_RECORDER_REAL")
round=$(mktemp "$ARGV_RECORDER_CAPTURES/round.XXXXXX")
printf '%s\0' "$@" > "$round.argv"
{ "$real" "$@"; echo $? > "$round.exit"; } | tee "$round.jsonl"
exit "$(cat "$round.exit")"
