# Writes OVERSIZED_BYTES bytes (70000 unless set: more than the factory's describe cap, 64 KiB) into
# the result file the factory named: a JSON object opened and then padded with spaces.
printf '{"version":"1",' > "$GNOMISH_RESULT_FILE"
head -c "${OVERSIZED_BYTES:-70000}" /dev/zero | tr '\0' ' ' >> "$GNOMISH_RESULT_FILE"
