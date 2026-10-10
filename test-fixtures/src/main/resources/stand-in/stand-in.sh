#!/bin/sh
# The committed stand-in binary (ADR 0015). Every preset is a symbolic link `links/<preset>` to
# this one script and a section `[<preset>]` in one of the tables `tables/*.params`, several
# presets to a file. A spec runs the link itself, or a per-run link to it that StandIn creates
# where it needs a name, a log or files beside it; either way the preset is the committed link's
# name. Files the rows name are paths relative to this library: `data/` answers, `steps/` shell run
# through sh. One row per line; `#` starts a comment line:
#
#   <match> <action> [arguments]
#
# <match> is `*` (every invocation) or a comma-separated argv prefix, each word a shell glob matched
# against the argument at its position after the leading `-c <name>=<value>` pairs are dropped
# (`rev-parse,--git-common-dir`, `ls-remote,origin,HEAD`). The word `@name` stands for the per-run
# link's own name, literally: one preset refuses or stalls whichever subcommand its link is named.
#
# Rows are tried top to bottom. A step runs and the table goes on; a terminal action ends the run.
#   steps:    record [VAR ...]   append a block to `$0.log`: argv=<all arguments>, arg.N=<the Nth>
#                                each, pid=<pid>, VAR=[value] (or VAR=unset) per name, then `--`;
#                                in a value `\` stands for a backslash and `\n` for a line break
#             stdout <file>      copy <file> to stdout
#             stderr <file>      copy <file> to stderr
#             delay <seconds>    sleep, then go on
#             run <file>         run <file> with sh, with the invocation's arguments
#             write <file> @name copy <file> over the per-run file `$0.name`, so what a later
#                                invocation's `@name` row reads changes (a push that landed)
#             export NAME <word> export NAME=<word> to what this run executes
#             export-name NAME   export NAME=<the per-run link's own name>: one preset serves
#                                every value, the spec names the link after the one it means
#                                (an agent link named after its scenario)
#             close-stdout       close stdout and go on
#   terminal: exit <code>
#             answer <file> <code>   stdout <file>, then exit <code>
#             refuse <code> <file>   stderr <file>, then exit <code>
#             stall <seconds>        become `sleep <seconds>` (a kill reaches the sleep itself)
#             delegate <binary>      run the real <binary> from PATH (this stand-in's own
#                                    directories left out); after a `record`, append
#                                    `pid=`/`exit=` as one more block
#             exec-sh <file>         become `sh <file>` with the invocation's arguments
#
# <file> is a path relative to this library (`steps/…`), one answer of a sectioned file under
# `data/` (`data/stderr#unable-to-access`: the lines of `[unable-to-access]`, up to the blank line
# before the next section), `-` for nothing, or `@name`: the
# per-run file `$0.name` beside a per-run link, which the spec writes. <code> is a number or
# `@name`, read from that file. <word> is taken as written, or `@name` for that file's path.
#
# A preset writes only beside a per-run link — its `$0.log`, or a `$0.name` a `write` row names:
# a `record`, `write`, `export-name` or `@name` (in any column) reached through the committed link is
# refused (exit 97), so the library stays read-only and a link's name is never mistaken for a value.
# A preset with no section, or in two, and an invocation no row matches are broken presets, not
# defaults: exit 97 with the reason on stderr.

invoked=$0
target=$(readlink "$invoked")
case "$target" in
    '')
        echo "stand-in: $invoked is not a link to a preset" >&2
        exit 97
        ;;
    */stand-in.sh | stand-in.sh)
        direct=yes
        links=$(dirname "$invoked")
        preset=${invoked##*/}
        ;;
    *)
        direct=
        links=$(dirname "$target")
        preset=${target##*/}
        ;;
esac
library="$links/.."
table="[$preset]"
found=$(grep -l -x -F "[$preset]" "$library"/tables/*.params)
case "$found" in
    *"
"*)
        echo "stand-in: preset $preset has a section in more than one table" >&2
        exit 97
        ;;
    '')
        echo "stand-in: preset $preset has no section under $library/tables" >&2
        exit 97
        ;;
esac
table="$found [$preset]"
section=$(awk -v head="[$preset]" '$0 == head { on = 1; next } /^\[/ { on = 0 } on' "$found")

broken() {
    echo "stand-in: $1 ($table)" >&2
    exit 97
}

# Each resolver sets `resolved` rather than printing, so a refusal ends this run and not only a
# command substitution.
per_run() {
    [ -z "$direct" ] || broken "@$1 needs a per-run link"
    resolved="$invoked.$1"
}

file_of() {
    answer=
    case "$1" in
        -) resolved=/dev/null ;;
        @*) per_run "${1#@}" ;;
        '') broken 'a file argument is missing' ;;
        *#*)
            resolved="$library/${1%%#*}"
            answer=${1#*#}
            grep -q -x -F "[$answer]" "$resolved" 2>/dev/null || broken "$1 names no answer"
            ;;
        *) resolved="$library/$1" ;;
    esac
}

# Prints what file_of resolved: the whole file, or one answer of a sectioned file — its lines up to
# the next `[name]`, less the one blank line that separates it from that header.
emit() {
    if [ -z "$answer" ]; then
        cat "$resolved"
        return
    fi
    awk -v head="[$answer]" '
        $0 == head { on = 1; next }
        on && /^\[[^]]+\]$/ { exit }
        on { buf[++n] = $0 }
        END { if (n > 0 && buf[n] == "") n--; for (i = 1; i <= n; i++) print buf[i] }
    ' "$resolved"
}

code_of() {
    case "$1" in
        @*) per_run "${1#@}"; resolved=$(cat "$resolved") ;;
        *#*) file_of "$1"; resolved=$(emit) ;;
        '') broken 'an exit code is missing' ;;
        *) resolved=$1 ;;
    esac
}

matches() {
    pattern=$1
    shift
    while [ "$1" = "-c" ] && [ $# -ge 2 ]; do
        shift 2
    done
    [ "$pattern" = "*" ] && return 0
    set -f
    saved=$IFS
    IFS=,
    for word in $pattern; do
        IFS=$saved
        if [ $# -eq 0 ]; then
            set +f
            return 1
        fi
        if [ "$word" = "@name" ]; then
            [ -z "$direct" ] || broken "@name needs a per-run link"
            [ "$1" = "${invoked##*/}" ] || { set +f; return 1; }
            shift
            continue
        fi
        case "$1" in
            $word) shift ;;
            *) set +f; return 1 ;;
        esac
    done
    IFS=$saved
    set +f
    return 0
}

# One line per value: a backslash becomes `\\` and a line break `\n`, so a value holding either
# (a commit message) reads back whole. Pure parameter expansion, no process per value.
newline='
'
escape() {
    rest=$1
    escaped=
    while :; do
        case "$rest" in
            *\\*) escaped="$escaped${rest%%\\*}\\\\"; rest=${rest#*\\} ;;
            *) escaped="$escaped$rest"; break ;;
        esac
    done
    rest=$escaped
    escaped=
    while :; do
        case "$rest" in
            *"$newline"*) escaped="$escaped${rest%%"$newline"*}\\n"; rest=${rest#*"$newline"} ;;
            *) escaped="$escaped$rest"; break ;;
        esac
    done
}

record() {
    names=$1
    shift
    per_run log
    log=$resolved
    {
        escape "$*"
        printf 'argv=%s\n' "$escaped"
        position=0
        for argument in "$@"; do
            position=$((position + 1))
            escape "$argument"
            printf 'arg.%s=%s\n' "$position" "$escaped"
        done
        printf 'pid=%s\n' "$$"
        for name in $names; do
            case "$name" in
                *[!A-Za-z0-9_]* | [0-9]*) broken "'$name' is not a variable name" ;;
            esac
            if eval "[ -n \"\${$name+set}\" ]"; then
                eval "escape \"\$$name\""
                printf '%s=[%s]\n' "$name" "$escaped"
            else
                printf '%s=unset\n' "$name"
            fi
        done
        printf '%s\n' '--'
    } >> "$log"
    recorded=yes
}

path_without_self() {
    own=$(dirname "$invoked")
    kept=
    saved=$IFS
    IFS=:
    for entry in $PATH; do
        if [ "$entry" != "$own" ] && [ "$entry" != "$links" ]; then
            kept="${kept:+$kept:}$entry"
        fi
    done
    IFS=$saved
    printf '%s' "$kept"
}

exec 3<<EOF
$section
EOF
recorded=
while IFS= read -r row <&3; do
    case "$row" in
        '' | '#'*) continue ;;
    esac
    read -r match action args <<EOF
$row
EOF
    matches "$match" "$@" || continue
    case "$action" in
        record) record "$args" "$@" ;;
        stdout) file_of "$args"; emit ;;
        stderr) file_of "$args"; emit >&2 ;;
        delay) sleep "$args" ;;
        run) file_of "$args"; sh "$resolved" "$@" ;;
        write)
            read -r first second <<EOF
$args
EOF
            case "$second" in
                @*) ;;
                *) broken "write needs an @name target, not '$second'" ;;
            esac
            file_of "$second"
            destination=$resolved
            file_of "$first"
            emit > "$destination"
            ;;
        export)
            read -r name word <<EOF
$args
EOF
            case "$word" in
                @*) per_run "${word#@}"; word=$resolved ;;
            esac
            export "$name=$word"
            ;;
        export-name)
            [ -z "$direct" ] || broken "export-name needs a per-run link"
            export "$args=${invoked##*/}"
            ;;
        close-stdout) exec 1>&- ;;
        exit)
            exec 3<&-
            code_of "$args"
            exit "$resolved"
            ;;
        answer)
            read -r first second <<EOF
$args
EOF
            exec 3<&-
            file_of "$first"
            emit
            code_of "$second"
            exit "$resolved"
            ;;
        refuse)
            read -r first second <<EOF
$args
EOF
            exec 3<&-
            file_of "$second"
            emit >&2
            code_of "$first"
            exit "$resolved"
            ;;
        stall)
            exec 3<&-
            exec sleep "$args"
            ;;
        delegate)
            exec 3<&-
            PATH=$(path_without_self)
            export PATH
            if [ -n "$recorded" ]; then
                "$args" "$@"
                code=$?
                per_run log
                printf 'pid=%s\nexit=%s\n%s\n' "$$" "$code" '--' >> "$resolved"
                exit "$code"
            fi
            exec "$args" "$@"
            ;;
        exec-sh)
            exec 3<&-
            file_of "$args"
            exec sh "$resolved" "$@"
            ;;
        *) broken "unknown action '$action'" ;;
    esac
done
broken "no row matches: $*"
