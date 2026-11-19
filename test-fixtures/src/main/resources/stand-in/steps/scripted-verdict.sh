# A pollable verdict program whose run lives outside its working copy (design D11 as amended
# 2026-11-14 of migrate-verify-to-executor-contract): each `start` and `poll` appends
# `<operation> <GNOMISH_PENDING_KEY or -> <HEAD of the working copy> <working copy>` to HEADS_FILE,
# then answers the first line of SCRIPT_FILE — a result the spec wrote, pending with its key or
# terminal — over GNOMISH_RESULT_FILE, and drops that line. Both files are per-run files beside the
# spec's link (`[verdict-scripted]` exports their paths); nothing is written in the working copy.
printf '%s %s %s %s\n' "$1" "${GNOMISH_PENDING_KEY:--}" "$(git rev-parse HEAD)" "$(pwd -P)" >> "$HEADS_FILE"
head -n 1 "$SCRIPT_FILE" > "$GNOMISH_RESULT_FILE"
tail -n +2 "$SCRIPT_FILE" > "$SCRIPT_FILE.rest"
mv "$SCRIPT_FILE.rest" "$SCRIPT_FILE"
