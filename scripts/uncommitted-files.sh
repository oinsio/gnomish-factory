#!/bin/bash
# Maintains the uncommitted-files.md checklist used by /fix-uncommitted.
# Compatible with bash 3.x (macOS) and bash 4+ (Linux).
#
# Usage:
#   ./scripts/uncommitted-files.sh [sync]   merge git status into the checklist,
#                                           preserving progress (default)
#   ./scripts/uncommitted-files.sh reset    discard the checklist and rebuild it
#   ./scripts/uncommitted-files.sh next     print the first unchecked path, or ALL DONE
#   ./scripts/uncommitted-files.sh done <path>   mark a path done and stamp its hash
#
# Scope: source files of every Gradle module — any path containing a `src/`
# segment (`domain/src/...`, `adapters/github/src/...`, `build-logic/src/...`).
# Since the multi-module split there is no root `src/`.
#
# Line format: `N. [ ] path` / `N. [x] path <!-- <blob-sha> -->`. The hash is the
# file content at the moment `done` was run, so `sync` can demote an item back to
# `[ ]` when the file was touched again after being checked.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
CHECKLIST="$REPO_ROOT/uncommitted-files.md"
CHECKLIST_NAME="uncommitted-files.md"
TAB="$(printf '\t')"

# Collects every uncommitted/untracked source file, one path per line, sorted.
#
# `--porcelain -z` is used instead of the line format + `awk '{print $NF}'`:
# NUL-separated records keep paths with spaces intact and are never quoted, and
# rename records ("XY new\0old\0") are split correctly instead of yielding the
# "->" arrow. `-uall` lists files inside untracked directories rather than
# collapsing them to a single folder entry.
collect_files() {
  local record status path
  while IFS= read -r -d '' record; do
    status="${record:0:2}"
    path="${record:3}"
    # Rename/copy records carry the source path as the next NUL record.
    case "$status" in
      R*|C*|*R|*C) IFS= read -r -d '' _source || true ;;
    esac
    [[ "$path" == "$CHECKLIST_NAME" ]] && continue
    # Module-scoped source files only: some path segment must be `src`.
    [[ "$path" == src/* || "$path" == */src/* ]] || continue
    # Deleted (or otherwise absent) paths have nothing left to diagnose.
    [[ -f "$REPO_ROOT/$path" ]] || continue
    echo "$path"
  done < <(git -C "$REPO_ROOT" status --porcelain -z -uall) | sort -u
}

# Emits "<hash>\t<path>" for every checked item that carries a stamp.
parse_checked() {
  [[ -f "$CHECKLIST" ]] || return 0
  awk -v tab="$TAB" '
    match($0, /^[0-9]+\. \[x\] /) {
      rest = substr($0, RLENGTH + 1)
      if (match(rest, / <!-- [0-9a-f]+ -->$/)) {
        print substr(rest, RSTART + 6, RLENGTH - 10) tab substr(rest, 1, RSTART - 1)
      }
    }' "$CHECKLIST"
}

# Content hash of a working-tree file, in git's own blob format.
file_hash() {
  git -C "$REPO_ROOT" hash-object -- "$1"
}

# Rewrites the checklist from git status, carrying over still-valid `[x]` marks.
sync_checklist() {
  local git_files state merged counter prev_hash file mark stamp
  git_files="$(mktemp)"; state="$(mktemp)"; merged="$(mktemp)"
  trap 'rm -f "$git_files" "$state" "$merged"' RETURN

  collect_files > "$git_files"
  if [[ ! -s "$git_files" ]]; then
    echo "No uncommitted or untracked source files found."
    [[ -f "$CHECKLIST" ]] && rm "$CHECKLIST"
    return 0
  fi

  parse_checked > "$state"
  # Left-join the recorded hashes onto the current file list: "<hash|empty>\t<path>".
  # Keyed on FILENAME, not the usual NR==FNR: an empty state file makes NR==FNR
  # true for the *first* line of the second file, swallowing it into the map.
  # "-" stands in for "no recorded hash": tab counts as IFS whitespace, so an
  # empty first field would be stripped and `read` would shift path into it.
  awk -F"$TAB" -v tab="$TAB" -v statefile="$state" '
    FILENAME == statefile { seen[$2] = $1; next }
    { print ($0 in seen ? seen[$0] : "-") tab $0 }' "$state" "$git_files" > "$merged"

  counter=1
  while IFS="$TAB" read -r prev_hash file; do
    mark=" "; stamp=""
    # A recorded hash that still matches means the file is untouched since it was
    # checked; anything else (no record, or edited afterwards) needs another pass.
    if [[ "$prev_hash" != "-" && "$(file_hash "$file")" == "$prev_hash" ]]; then
      mark="x"; stamp=" <!-- $prev_hash -->"
    fi
    echo "$counter. [$mark] $file$stamp"
    counter=$((counter + 1))
  done < "$merged" > "$CHECKLIST"

  local total done_count
  total=$((counter - 1))
  done_count="$(grep -c '^[0-9]*\. \[x\]' "$CHECKLIST" || true)"
  echo "Done. $total file(s) in uncommitted-files.md, $done_count already checked."
}

# Prints the first unchecked path, or ALL DONE when the list is exhausted.
next_item() {
  [[ -f "$CHECKLIST" ]] || { echo "No checklist — run 'sync' first." >&2; exit 1; }
  local path
  path="$(sed -n '/^[0-9]*\. \[ \] /{s/^[0-9]*\. \[ \] \(.*\)$/\1/p;q;}' "$CHECKLIST")"
  [[ -n "$path" ]] && echo "$path" || echo "ALL DONE"
}

# Marks one path done and stamps its current content hash.
mark_done() {
  local target="$1" hash tmp
  [[ -f "$CHECKLIST" ]] || { echo "No checklist — run 'sync' first." >&2; exit 1; }
  target="${target#"$REPO_ROOT"/}"; target="${target#./}"
  [[ -f "$REPO_ROOT/$target" ]] || { echo "No such file: $target" >&2; exit 1; }
  hash="$(file_hash "$target")"
  tmp="$(mktemp)"
  awk -v target="$target" -v hash="$hash" '
    match($0, /^[0-9]+\./) {
      num = substr($0, 1, RLENGTH)
      rest = substr($0, RLENGTH + 1)
      sub(/^ \[[ x]\] /, "", rest)
      sub(/ <!-- [0-9a-f]+ -->$/, "", rest)
      if (rest == target) {
        print num " [x] " target " <!-- " hash " -->"
        found = 1
        next
      }
    }
    { print }
    END { if (!found) exit 3 }' "$CHECKLIST" > "$tmp" ||
    { rm -f "$tmp"; echo "Not in checklist: $target" >&2; exit 1; }
  mv "$tmp" "$CHECKLIST"
  # Progress counters come from here so the caller can log "<n>/<total>" without
  # re-reading the checklist itself.
  local total checked
  total="$(grep -c '^[0-9]*\. \[' "$CHECKLIST" || true)"
  checked="$(grep -c '^[0-9]*\. \[x\]' "$CHECKLIST" || true)"
  echo "Checked $checked/$total: $target"
}

case "${1:-sync}" in
  sync) sync_checklist ;;
  reset) rm -f "$CHECKLIST"; sync_checklist ;;
  next) next_item ;;
  done) [[ $# -ge 2 ]] || { echo "Usage: $0 done <path>" >&2; exit 1; }; mark_done "$2" ;;
  *) sed -n '2,18p' "$0" >&2; exit 1 ;;
esac
