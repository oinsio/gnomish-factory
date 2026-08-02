package com.github.oinsio.gnomish.app.serve;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs {@link FeedAutomaton} state transitions — never every cycle spent in the same state, only
 * the moment the observed state changes — so an operator watching the daemon log sees one line
 * per bottleneck, not one every idle-poll interval (default 30s) forever (UX2: "the operator
 * learns it from one log line").
 *
 * <p>Uses the {@link FeedAutomaton} logger name (not its own class) so the log output reads as
 * coming from the automaton, which is the only public-facing type here.
 *
 * <p>{@code IDLE_BLOCKED} and {@code FULL} log at INFO — the two "interesting" operational states
 * per UX2 that name a concrete bottleneck an operator must act on (a human decision, or slots all
 * occupied). {@code FILLING} and {@code IDLE_EMPTY} log at DEBUG — routine progress and an empty
 * queue are not, by themselves, actionable.
 *
 * <p>The {@code IDLE_BLOCKED} line names the open-front count and states plainly that fresh work
 * is not starting, per NFR-O1's "the bottleneck (the human) is named, not silent".
 *
 * <p>Implements NFR-O1 of add-factory-serve.
 */
final class FeedStateLogger {

    private static final Logger log = LoggerFactory.getLogger(FeedAutomaton.class);

    private @Nullable FeedState previousState;

    /**
     * The only vantage point {@code FULL} can be observed from without a busy-poll: called right
     * after the claim that spends the last free slot, before the next cycle's {@code
     * SlotLedger#acquire()} would block. A no-op unless {@code freeSlots} is zero.
     */
    void onSlotFilled(int freeSlots, int wipLimit) {
        if (freeSlots == 0) {
            onTransition(FeedState.FULL, 0, wipLimit);
        }
    }

    /**
     * Logs {@code newState} only if it differs from the state last reported to this method.
     *
     * @param newState the state observed this cycle (or the synthetic {@code FULL} vantage
     *     point via {@link #onSlotFilled}); never null
     * @param openFrontCount the current count of open fronts, named in the {@code IDLE_BLOCKED}
     *     line; only meaningful for that case
     * @param wipLimit the configured WIP limit W, named alongside {@code openFrontCount}
     */
    void onTransition(FeedState newState, int openFrontCount, int wipLimit) {
        if (newState == previousState) {
            return;
        }
        previousState = newState;
        switch (newState) {
            case IDLE_BLOCKED ->
                log.info(
                        "{} front(s) await human decisions; not starting fresh work (WIP limit {})",
                        openFrontCount,
                        wipLimit);
            case FILLING -> log.debug("feed state transitioned to FILLING");
            case IDLE_EMPTY -> log.debug("feed state transitioned to IDLE_EMPTY");
            case FULL -> log.info("feed state transitioned to FULL: all slots occupied, pausing new claims");
        }
    }
}
