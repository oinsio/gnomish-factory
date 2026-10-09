package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.Stop;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * The one mapping between a round's domain {@link Stop} and {@code state.json}'s attempt {@code
 * stop} object ({@link StateStopDto}), both ways. Every sealed type is switched exhaustively with
 * no {@code default} arm, so a new {@code Stop} variant fails to compile here until it is mapped.
 * An absent {@code stop} — a document written before the field existed — reads as {@code none}
 * (FR10, {@link #absentAsNone}).
 *
 * <p>Split out of {@link StateJsonMapper} when the stop arrived, to keep that file within the
 * project's file-size invariant: the stop vocabulary is its own wire table.
 *
 * <p>An {@link UntrustedExit} for the same reason as {@link StateJsonMapper}: it writes the stop's
 * question, options, reason and details verbatim into the branch document, and re-mints each one
 * as branch text on the way back (design D3 of type-untrusted-text).
 *
 * <p>Implements FR5, FR10 of make-checkpoint-gate-durable (design D3, D5).
 */
@UntrustedExit
final class StateStopMapper {

    private StateStopMapper() {}

    static StateStopDto toDto(Stop stop) {
        return switch (stop) {
            case Stop.None ignored -> new StateStopDto.None("none");
            case Stop.DecisionNeeded decision ->
                new StateStopDto.DecisionNeeded(
                        "decisionNeeded",
                        decision.question().raw(),
                        decision.options().stream().map(UntrustedText::raw).toList());
            case Stop.CannotVerify cannotVerify ->
                new StateStopDto.CannotVerify(
                        "cannotVerify",
                        cannotVerify.check().label().raw(),
                        cannotVerify.reason().raw(),
                        cannotVerify.details().raw());
        };
    }

    static Stop fromDto(StateStopDto dto) {
        return switch (dto) {
            case StateStopDto.None ignored -> Stop.none();
            case StateStopDto.DecisionNeeded decision ->
                new Stop.DecisionNeeded(
                        UntrustedText.branchDocument(decision.question()),
                        decision.options().stream()
                                .map(UntrustedText::branchDocument)
                                .toList());
            case StateStopDto.CannotVerify cannotVerify ->
                new Stop.CannotVerify(
                        // The wire carries the check's label only; index 0 is a placeholder (see
                        // StateJsonMapper's class-level note).
                        new CheckRef(0, UntrustedText.branchDocument(cannotVerify.check())),
                        UntrustedText.branchDocument(cannotVerify.reason()),
                        UntrustedText.branchDocument(cannotVerify.details()));
        };
    }

    /**
     * Normalizes an absent {@code stop} — a document written before the field existed — to {@code
     * none} (FR10), for {@link StateAttemptDto}'s compact constructor.
     *
     * <p>Kept as an explicit static method rather than inline in the compact constructor — PIT's
     * record filter suppresses mutations inside a record's canonical constructor, which would
     * exempt this default from the mutation gate.
     */
    static StateStopDto absentAsNone(@Nullable StateStopDto stop) {
        return stop == null ? new StateStopDto.None("none") : stop;
    }
}
