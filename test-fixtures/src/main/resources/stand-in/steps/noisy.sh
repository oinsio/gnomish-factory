# Writes $NOISY_LINE as a line $NOISY_LINES times to each stream, then exits cleanly: more than any
# OS pipe buffer. The preset that runs it, or the spec that starts it, sets both.
i=0
while [ $i -lt "$NOISY_LINES" ]; do
    printf '%s\n' "$NOISY_LINE"
    printf '%s\n' "$NOISY_LINE" >&2
    i=$((i + 1))
done
exit 0
