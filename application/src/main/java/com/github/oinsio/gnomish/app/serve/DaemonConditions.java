package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.app.take.TakeResult;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one owner of a {@code serve} daemon's conditions (design D19 of add-sandbox-base-image,
 * single-owner row 7): the set of {@link DaemonCondition}s, one per {@link DaemonConditionKind},
 * the feed's one question before a claim ({@link #anyOpen()}), the one step that advances every due
 * probe ({@link #probeDue()}), and the one entry a typed precondition refusal opens its condition
 * through ({@link #report}). The feed reads this owner and nothing else, so every condition in the
 * set holds the feed without a second gate read anywhere.
 *
 * <p>Every member guards its own state ({@code lock-scope.md}), so this class holds none: a refusal
 * a probe hands back is reported after that probe's monitor was released.
 *
 * <p>Implements FR15, FR16, D19 of add-sandbox-base-image; FR14 of add-base-ref-resolution.
 */
public final class DaemonConditions {

    private final DaemonCondition remote;
    private final List<DaemonCondition> all;
    private final Map<DaemonConditionKind, DaemonCondition> byKind = new EnumMap<>(DaemonConditionKind.class);

    /**
     * @param remote the remote's condition, the one the slots' git decorations signal; never null
     * @param preconditions one condition per operator precondition the daemon holds; empty for a set
     *     that holds the remote alone; never null, no kind twice
     */
    DaemonConditions(DaemonCondition remote, List<DaemonCondition> preconditions) {
        this.remote = remote;
        List<DaemonCondition> members = new ArrayList<>();
        members.add(remote);
        members.addAll(preconditions);
        for (DaemonCondition member : members) {
            if (byKind.put(member.kind(), member) != null) {
                throw new IllegalArgumentException("two conditions on " + member.kind());
            }
        }
        this.all = List.copyOf(members);
    }

    /**
     * Every condition of the set, the remote's first, for the snapshot's {@code conditions}
     * section (NFR-O2 of add-sandbox-base-image).
     *
     * @return the members in their fixed order; never null
     */
    public List<DaemonCondition> members() {
        return all;
    }

    /** The remote's condition, which the slots' git decorations open and the snapshot reads. */
    public DaemonCondition remote() {
        return remote;
    }

    /**
     * Opens the condition a typed refusal names (FR15): a slot's {@code OperatorPreconditionUnmet}
     * or the janitor's. A no-op while that condition is already open, so many slots meeting one
     * broken precondition write one WARN.
     *
     * @param refusal the typed refusal; never null
     * @throws IllegalArgumentException when this set holds no condition on the refusal's kind
     */
    public void report(TakeResult.OperatorPreconditionUnmet refusal) {
        DaemonCondition condition = byKind.get(refusal.kind());
        if (condition == null) {
            throw new IllegalArgumentException("no condition on " + refusal.kind());
        }
        condition.openOnFailure(refusal.reason().forLog());
    }

    /**
     * The operator preconditions whose condition is open (design D21): the janitor's branch pass
     * meets the same checks, so it waits while any is open rather than meeting them again.
     *
     * @return the open precondition kinds, the remote never among them; never null
     */
    Set<DaemonConditionKind> openPreconditions() {
        Set<DaemonConditionKind> open = EnumSet.noneOf(DaemonConditionKind.class);
        all.stream()
                .filter(DaemonCondition::isOpen)
                .map(DaemonCondition::kind)
                .filter(kind -> kind != DaemonConditionKind.REMOTE)
                .forEach(open::add);
        return open;
    }

    /** Whether any condition holds the feed off the tracker; read once before every claim. */
    boolean anyOpen() {
        return all.stream().anyMatch(DaemonCondition::isOpen);
    }

    /**
     * Records, on every condition, that the feed abandoned a permit with no claim attempt, for each
     * open episode's eventual ledger line. A closed condition needs no filter: its count is reset
     * when it next opens, so a stray count is never reported.
     */
    void claimReleasedWhileOpen() {
        all.forEach(DaemonCondition::claimReleasedWhileOpen);
    }

    /**
     * Runs every probe that is due, the remote's first; a refusal of another precondition a probe
     * met first opens that one. A probe that throws is its own condition's failed probe ({@link
     * DaemonCondition#probeIfDue}); nothing propagates to the feed, and the later probes run.
     */
    void probeDue() {
        for (DaemonCondition condition : all) {
            condition.probeIfDue().ifPresent(this::report);
        }
    }
}
