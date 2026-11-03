# Copies the request the factory handed on standard input beside the answer file the factory named,
# as `<GNOMISH_ANSWER_FILE>.request`: a spec reads back the whole request a run received through the
# environment's channel, and the answer file itself stays unwritten. Without the variable — no
# factory run — nothing is read.
if [ -n "${GNOMISH_ANSWER_FILE:-}" ]; then
    cat > "$GNOMISH_ANSWER_FILE.request"
fi
