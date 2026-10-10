# Runs in the receiving repository: the marker lives in its git directory.
marker="$(git rev-parse --git-dir)/rejected-once"
if [ ! -f "$marker" ]; then
    : > "$marker"
    echo transient >&2
    exit 1
fi
exit 0
