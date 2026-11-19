# The body of the pre-receive hook [hook-hold-pending-push] (hook.params): runs in the receiving
# repository, reads the pushed `<old> <new> <ref>` lines, and when a new tip is a factory pending
# commit (subject `gnomish: pending …`) appends the refs to HOLD_LOG and becomes a sleep, so the push
# neither lands nor fails until the pushing process and the hook are killed. The sleep is a bound on
# a forgotten hook, never an order: the spec reads HOLD_LOG and kills. Any other push is let through.
held=
while read -r old new ref; do
    case "$(git log -1 --format=%s "$new" 2>/dev/null)" in
        'gnomish: pending '*) held="$held $ref" ;;
    esac
done
[ -n "$held" ] || exit 0
printf '%s\n' "${held# }" >> "$HOLD_LOG"
exec sleep 900
