# Changes the working copy a run starts in, the way EDIT names: `modify` appends to the tracked
# `tracked.txt`, `untracked` creates `judge-notes.txt`, `chmod` makes `tracked.txt` executable,
# `commit` commits an empty commit, `rename` stages `tracked.txt` as `moved.txt`, `ungit` removes
# the working copy's `.git`, `files-<n>` creates `new-1.txt` to `new-<n>.txt`, `none` changes
# nothing. Its own git runs with no hook, signing or filesystem monitor, so a planted one stays
# unrun. The subject is the working-copy check of a verdict or decision run (task 7.2 of
# define-executor-contract).
set -eu
quiet='-c core.hooksPath=/dev/null -c core.fsmonitor=false -c commit.gpgSign=false'
case "$EDIT" in
    modify) echo 'the judge was here' >> tracked.txt ;;
    untracked) echo 'notes' > judge-notes.txt ;;
    chmod) chmod +x tracked.txt ;;
    commit) git $quiet -c user.email=j@b.c -c user.name=judge commit -q --allow-empty -m judge ;;
    rename) git $quiet mv tracked.txt moved.txt ;;
    ungit) rm -rf .git ;;
    files-*)
        i=1
        while [ "$i" -le "${EDIT#files-}" ]; do echo "$i" > "new-$i.txt"; i=$((i+1)); done
        ;;
    none) ;;
    *) echo "working-copy-edit: unknown EDIT '$EDIT'" >&2; exit 97 ;;
esac
