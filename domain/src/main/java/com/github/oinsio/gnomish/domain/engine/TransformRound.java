package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import java.util.List;

/**
 * The requests of one {@code transform} round (design D11 of extract-agent-round-runner): the round's
 * {@code start}, and — when it answers {@code pending} and the stage's executor declares {@code poll}
 * ({@link DescribedExecutors#polls}) — the hand-off to {@link PendingPoll}, which writes the pending
 * round commit before the first poll and polls at the engine's pace until a terminal answer or the
 * factory's pending ceiling. A {@code pending} from an executor declaring no {@code poll} is returned
 * as it came, for {@link ExecutorOutcomeClassifier} to refuse (wire-contract "Pending from a role
 * whose caller cannot poll"): nothing is written and nothing is polled.
 *
 * <p>A resuming run's pending round (design D11, "Resume"; crash-consistency item 14) is continued
 * with no {@code start}: its entry, read off the registered commit and accepted against the round the
 * run's cell was restored with ({@link RoundTokenTake#resumedEntry}), goes to {@link
 * PendingPoll#resumeRound}, the same wait path a live round's polls take; its result is the terminal
 * answer of a poll or the ceiling, never a {@code Completed} the round did not report.
 *
 * <p>While the round is pending no verify chain runs and no stage advances: the pending round commit
 * keeps the position and records no attempt, and only the terminal answer is classified and closes
 * the round through the normal close, whose state — built by every recording writer of {@link
 * TaskState} — carries no pending entry (crash-consistency items 12, 13).
 *
 * <p>Holds only immutable collaborators (NFR-R1).
 *
 * <p>Implements FR10, FR11, NFR-R3, NFR-R4, NFR-O2, UX3 of extract-agent-round-runner.
 */
final class TransformRound {

    private final StageExecutor executor;
    private final PendingPoll poll;
    private final DescribedExecutors described;
    private final PendingBounds bounds;

    /**
     * @param ports the run's executor, described executors and pending bounds; never null
     * @param poll the engine's one owner of pending work; never null
     */
    TransformRound(EnginePorts ports, PendingPoll poll) {
        this.executor = ports.executor();
        this.poll = poll;
        this.described = ports.describedExecutors();
        this.bounds = ports.pendingBounds();
    }

    /**
     * Runs the round's requests: its {@code start}, and its polls when it went pending on an executor
     * declaring {@code poll}; or, for the pending round a resuming run continues, its polls alone.
     *
     * @param start the round's {@code start} request; never null
     * @param key the round's correlation key; never null
     * @param state the last persisted state the round ran from, which its pending write carries; never
     *     null
     * @param token the round's one token take; never null
     * @return what the round's requests came to; never null
     */
    PendingRun run(StageExecutor.Request start, AttemptKey key, TaskState state, RoundTokenTake token) {
        var recorded = token.resumedEntry(state.pendingPolls(), key.attempt());
        if (recorded.isPresent()) {
            return poll.resumeRound(ask(start, key, state, token), recorded.get());
        }
        var started = executor.execute(start);
        if (ExecutorOutcomeClassifier.pending(started).isEmpty() || !described.polls(start.stage())) {
            return new PendingRun(started, null, null, List.of(started.usage()));
        }
        return poll.continueStarted(ask(start, key, state, token), started);
    }

    /** The round's wait: its own request, key, last persisted state and token, its executor's name. */
    private RoundAsk ask(StageExecutor.Request start, AttemptKey key, TaskState state, RoundTokenTake token) {
        var name = described.executorOf(start.stage()).name();
        return new RoundAsk(start, key, state, token.token(), name, bounds);
    }
}
