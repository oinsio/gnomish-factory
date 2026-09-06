package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto;
import com.github.oinsio.gnomish.adapter.git.state.RecordedDenialIdentities;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import com.github.oinsio.gnomish.sandbox.DenialRestoration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a branch tip offers a resuming instance about denials already reported (FR4, FR5, FR7 of
 * fix-denial-attribution-durability): the newest committed read position across the tip's two
 * envelopes — {@code state.json}'s attempt-side cursor and {@code task.json}'s escalation-side
 * cursor — together with the identities of the denials those envelopes record.
 *
 * <p>The tip is read through the branch-shape classifier of the {@code task-branch-contract}
 * capability, not through an ad-hoc file-presence check: a position is offered only for a shape
 * whose tip carries state, so an unsupported envelope version, a corrupt tip, or any other
 * quarantining shape yields none rather than a position parsed out of a document the factory has
 * already refused to trust.
 *
 * <p>An offer is not an instruction (FR5 of fix-denial-report-attachment): the environment applies
 * a position only when its paired source identity is its own live denial source, and reads its log
 * from the start otherwise. So every ambiguity here degrades to a full re-read — never silence
 * (design D3) — and the identities that travel beside the position turn that re-read into a merge
 * rather than a duplicated report (FR7).
 */
public final class TipRecordedDenials {

    private static final Logger log = LoggerFactory.getLogger(TipRecordedDenials.class);

    private final TipEnvelopeReader envelopes = new TipEnvelopeReader();

    /**
     * What {@code source}'s tip records about denials already reported, or {@link
     * DenialRestoration#none()} when it records nothing.
     *
     * @param source the medium to read the tip through
     * @return the newest committed position at the tip and the identities recorded with it
     */
    public DenialRestoration restorable(BranchTipSource source) {
        return switch (envelopes.read(source)) {
            case TipEnvelopeRead.NoState(BranchShape shape) -> {
                // LogText wrapper on a shape name that is a sealed record's own simple name and so
                // cannot be attacker-influenced: the untrusted-text gate recognizes label() by name
                // and cannot tell this one from CheckRef's, which really is manifest-derived.
                log.debug(
                        "branch tip is {}, which carries no envelopes to read a denial position from;"
                                + " the run reads its denial source from the start",
                        LogText.forLog(shape.label()));
                yield DenialRestoration.none();
            }
            case TipEnvelopeRead.Loaded(BranchShape ignored, String taskJson, String stateJson) -> {
                StateJsonDto state = StateJsonMapper.readDto(stateJson);
                TaskJsonDto task = TaskJsonMapper.readDto(taskJson);
                yield new DenialRestoration(
                        Optional.ofNullable(newest(state.egressCursor(), task.egressCursor()))
                                .map(c -> new DenialCursor(c.source(), c.position())),
                        RecordedDenialIdentities.of(state, task));
            }
        };
    }

    /**
     * The position to offer of the two the tip carries.
     *
     * <p>Positions minted by one denial source are that daemon's own timestamps, so when both name
     * the same source the later of the two instants wins outright — that is the whole
     * point of committing the escalation's position: it stands past denials the attempt-side cursor
     * does not cover.
     *
     * <p>When the two name <em>different</em> sources, one of them is a position of a guard the
     * factory has since recreated, and nothing at this layer can tell which: the live source's
     * identity is the environment's to know. The attempt-side cursor is offered, because {@code
     * state.json} is rewritten by every round and by every resume while {@code task.json}'s cursor
     * moves only when a park drains denials, so it is the fresher of the two in every ordinary
     * progression. Should the choice be wrong, the environment's own stamp check drops it and the
     * run re-reads the log from the start (FR4).
     */
    private static @Nullable EgressCursorDto newest(
            @Nullable EgressCursorDto attempt, @Nullable EgressCursorDto escalation) {
        if (attempt == null) {
            return escalation;
        }
        if (escalation == null) {
            return attempt;
        }
        if (!attempt.source().equals(escalation.source())) {
            return attempt;
        }
        return later(attempt, escalation);
    }

    /**
     * The later of two positions of the same source — daemon timestamps, ordered as instants.
     *
     * <p>Ordered by parsing, never by string comparison: a position is {@code Instant#toString}'s
     * rendering, whose fraction is 0, 3, 6 or 9 digits wide depending on the value, so two stamps
     * of one daemon can differ in width and lexicographic order then disagrees with time — {@code
     * 10:05:00Z} sorts <em>after</em> the later {@code 10:05:00.500Z}. Getting it wrong is not
     * symmetric and not caught downstream: both positions name the live source, so the environment
     * applies whichever arrives, and one too far forward silences every denial between the two.
     *
     * <p>A position that does not parse cannot be ordered here at all, and is treated exactly like
     * a position of a different source: the attempt-side one is offered, {@code state.json} being
     * the fresher envelope in every ordinary progression (FR4).
     */
    private static EgressCursorDto later(EgressCursorDto attempt, EgressCursorDto escalation) {
        Instant attemptAt = instantOf(attempt);
        Instant escalationAt = instantOf(escalation);
        if (attemptAt == null || escalationAt == null) {
            return attempt;
        }
        return escalationAt.isAfter(attemptAt) ? escalation : attempt;
    }

    /** The position as the instant its source stamped it, or null when it is not one. */
    private static @Nullable Instant instantOf(EgressCursorDto cursor) {
        try {
            return Instant.parse(cursor.position());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
