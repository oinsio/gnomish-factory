package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.domain.engine.TaskState;

/**
 * The runner-level result of one {@code take} run (design D2): {@link Delivered} —
 * the engine reached {@code Completed} and the task was finished on the tracker;
 * {@link AwaitingHuman} — the engine's outcome maps to a {@code park} call, carrying
 * the {@link ParkReason} that decides how the task can be resumed (design D3);
 * {@link Aborted} — a durability guarantee broke or the run crashed (task 5.3, not
 * produced by {@link TakeOutcomeMapper}); {@link Revoked} — the claim was lost
 * mid-run (task 5.5); {@link EmptyQueue} — bare auto mode's queue had nothing
 * eligible at all, the clean cron no-op (design D16); {@link Skipped} — no engine
 * run happened for any other reason, e.g. every eligible candidate lost its claim
 * race (later tasks).
 *
 * <p>This is deliberately a runner-level type, not a {@code TaskOutcome} variant
 * (design D2): the engine knows nothing about trackers, claims, or revocation: those
 * are external events of the run, layered on top by {@code app.take}. Every variant
 * carries the final {@link TaskState} it was produced from (except {@link EmptyQueue}
 * and {@link Skipped}, which have none, since no engine run happened) so a later
 * reporting step (task 5.11) can render a summary from the result alone, mirroring
 * how {@code TaskOutcome} carries {@code finalState} for the same reason.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR18, D2, D3 of add-tracker-port.
 */
public sealed interface TakeResult
        permits TakeResult.Delivered,
                TakeResult.AwaitingHuman,
                TakeResult.Aborted,
                TakeResult.Revoked,
                TakeResult.EmptyQueue,
                TakeResult.Skipped {

    /**
     * The engine reached {@code Completed}: the task was (or is about to be)
     * finished on the tracker with {@code summary} as the final report text
     * (FR18).
     *
     * <p>Implements FR18, D3 of add-tracker-port.
     *
     * @param finalState the final task state the engine returned; never null
     * @param summary finished text of the final report posted to the tracker;
     *     never blank
     */
    record Delivered(TaskState finalState, String summary) implements TakeResult {

        public Delivered {
            summary = requireNonBlank(summary, "summary");
        }
    }

    /**
     * The task was (or is about to be) parked into {@code AwaitingHuman} with
     * {@code reason} and {@code report} (design D3): {@code CHECKPOINT} for a
     * {@code manual} pipeline pause, {@code ESCALATION} for an escalation kind
     * that needs a human decision ({@code AttemptsExhausted}, {@code
     * DecisionNeeded}), {@code INFRA} for an escalation kind that needs a fix
     * followed by a retry ({@code CannotVerify}, {@code CannotExecute}, {@code
     * PipelineMismatch}).
     *
     * <p>Implements FR18, D3 of add-tracker-port.
     *
     * @param finalState the final task state the engine returned; never null
     * @param reason why the task was parked; never null
     * @param report finished text describing the situation and the return path;
     *     never blank
     */
    record AwaitingHuman(TaskState finalState, ParkReason reason, String report) implements TakeResult {

        public AwaitingHuman {
            report = requireNonBlank(report, "report");
        }
    }

    /**
     * A durability guarantee broke (engine {@code Aborted}) or the run itself
     * crashed with an uncaught exception (design D3). Not produced by {@link
     * TakeOutcomeMapper}; the abort path is task 5.3.
     *
     * <p>Implements D3 of add-tracker-port.
     *
     * @param finalState the last known task state; never null
     * @param cause free-text description of what went wrong; never blank
     */
    record Aborted(TaskState finalState, String cause) implements TakeResult {

        public Aborted {
            cause = requireNonBlank(cause, "cause");
        }
    }

    /**
     * The claim was lost mid-run: another instance (or a human) took the task
     * over while this instance was still executing (design D2). Not produced by
     * {@link TakeOutcomeMapper}; revocation handling is task 5.5.
     *
     * <p>Implements D2 of add-tracker-port.
     *
     * @param finalState the last known task state at the point of revocation;
     *     never null
     * @param note free-text salvage note left for the new claim holder; never
     *     blank
     */
    record Revoked(TaskState finalState, String note) implements TakeResult {

        public Revoked {
            note = requireNonBlank(note, "note");
        }
    }

    /**
     * Bare auto mode's {@code listReady} queue (after the abort-backoff
     * filter) had nothing eligible at all: a structurally empty queue, the
     * normal steady state of a cron factory (design D16, "a bare-take empty
     * queue is a clean no-op", proposal U4). Distinct from {@link Skipped}:
     * nothing existed to compete for, so there is nothing to name as a
     * refusal — carries no fields, since there is nothing to summarize.
     *
     * <p>Kept as its own variant rather than a {@link Skipped} message,
     * because exit-code mapping (design D16) needs to tell this case apart
     * from a genuine refusal at the type level, not by matching free text
     * (task 5.12): an empty queue exits 0, while every other {@link Skipped}
     * case — including "every eligible candidate lost the claim race" —
     * exits 15. That race-loss case is deliberately NOT {@link EmptyQueue}:
     * the queue was not empty, one or more real candidates existed and this
     * instance failed to secure any of them this round, which is
     * refusal-shaped ("nothing to take this run") rather than the queue
     * genuinely having nothing in it.
     *
     * <p>Implements D16 of add-tracker-port.
     */
    record EmptyQueue() implements TakeResult {}

    /**
     * No engine run happened at all: the run was skipped before {@code
     * engine.run} was ever called, e.g. every eligible candidate lost its
     * claim race on bare auto mode (later tasks). Carries no {@link
     * TaskState} — there is none to carry. Reserved for genuine refusals: an
     * empty queue is {@link EmptyQueue} instead (see its javadoc for the
     * reasoning behind the split).
     *
     * @param reason free-text description of why the run was skipped; never
     *     blank
     */
    record Skipped(String reason) implements TakeResult {

        public Skipped {
            reason = requireNonBlank(reason, "reason");
        }
    }

    /**
     * Fails fast on a blank value: every {@link TakeResult} variant's free-text
     * field must describe what happened, since a caller renders a report from it
     * alone (FR18). Kept as a shared static method rather than inline in each
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this
     * validation from the 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("TakeResult." + component + " must not be blank");
        }
        return value;
    }
}
