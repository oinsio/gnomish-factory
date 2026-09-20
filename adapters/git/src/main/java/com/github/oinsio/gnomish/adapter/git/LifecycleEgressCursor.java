package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which egress cursor a terminal lifecycle commit records (FR3, FR5 of
 * fix-denial-attribution-durability). Owns the {@link DenialCursorSource} seam and the one rule
 * that reads it, so {@link GitObjectsTaskRepository} owns only the commit protocol. Container mode
 * only: host mode runs no egress guard, so {@link GitTaskRepository} has no cursor of its own to
 * record and carries the tip's forward unchanged — there is no twin of this rule to keep in sync.
 *
 * <p>The position the environment's last denial read left behind is recorded when the escalation
 * being written is a {@code cannotExecute} carrying denials; on every other write the tip's own
 * cursor is carried forward unchanged.
 *
 * <p>Only that one escalation kind moves the cursor, because it is the only record on the lifecycle
 * write path that carries denials: the round died before its close, so no attempt record was built
 * and no {@code state.json} commit will delimit them. Recording a position on any other park would
 * put a position on the branch ahead of the record it delimits — the one failure mode design D3
 * rules out, since it silences the gap instead of duplicating it.
 *
 * <p>Best-effort (NFR-R1): an environment that cannot answer does not fail the park — the
 * escalation is committed with whatever position the tip already carried, which on a branch that
 * carried none leaves it cursorless. Losing the position costs a re-read, never a denial.
 */
final class LifecycleEgressCursor {

    private static final Logger log = LoggerFactory.getLogger(LifecycleEgressCursor.class);

    private final DenialCursorSource denialCursors;

    /**
     * @param denialCursors where a {@code cannotExecute} park reads the position its drained
     *     denials were read up to; {@link DenialCursorSource#NONE} where the run has no environment
     *     to ask
     */
    LifecycleEgressCursor(DenialCursorSource denialCursors) {
        this.denialCursors = denialCursors;
    }

    /**
     * Returns the cursor a lifecycle commit recording {@code lastEscalation} must carry.
     *
     * @param lastEscalation the escalation this commit records, or null when it records none
     * @param tipCursor the cursor the branch tip already carries, carried forward unless this write
     *     has a position of its own
     * @return the cursor to write into this commit's envelope, or null when neither source has one
     */
    @Nullable
    EgressCursorDto forEscalation(@Nullable EscalationReport lastEscalation, @Nullable EgressCursorDto tipCursor) {
        if (!(lastEscalation instanceof EscalationReport.CannotExecute cannotExecute)
                || cannotExecute.denials().isEmpty()) {
            return tipCursor;
        }
        Optional<DenialCursor> drained;
        try {
            drained = denialCursors.currentPosition();
        } catch (RuntimeException e) {
            log.warn(
                    OperatorEvent.ESCALATION_DENIAL_POSITION_UNREADABLE.head()
                            + "the environment could not answer its denial position while parking a"
                            + " cannotExecute escalation; recording the escalation with the position the"
                            + " branch tip already carried, so the next lease re-reads the guard log from"
                            + " where the last commit left it",
                    e);
            return tipCursor;
        }
        return drained.map(c -> new EgressCursorDto(c.source(), c.position())).orElse(tipCursor);
    }
}
