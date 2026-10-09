package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.domain.engine.Position;
import org.jspecify.annotations.Nullable;

/**
 * The one check that a {@code --decision} meets a task that can take one (FR9 of
 * make-run-headless): a decision answers a recorded escalation, so a resume carrying one over a gate
 * or over any other recorded outcome is a usage error naming the conflict — raised by both resume
 * routers ({@link GitResumeRunner}, {@link ContainerResumeRunner}) right after {@code task.json} and
 * {@code state.json} are read and before any branch write, so the branch is untouched when the
 * operator reads the message. A gate is refused whatever the outcome says: a checkpoint asks no
 * question, and a stale escalation under it is not its park (FR4 of make-checkpoint-gate-durable).
 *
 * <p>Implements FR9 of make-run-headless; FR4 of make-checkpoint-gate-durable.
 */
final class ResumeDecisionGuard {

    private ResumeDecisionGuard() {}

    /**
     * Refuses a decision over a gate or a non-escalated recorded outcome; a resume without a
     * decision passes whatever the branch records.
     *
     * @param taskId the resumed task, as the branch records it
     * @param outcome the branch's recorded outcome, {@code null} for an interrupted run
     * @param position the branch's recorded position
     * @param decision the operator's {@code --decision}, or {@code null} when none was given
     * @throws UsageException when {@code decision} is present and {@code position} is a gate or
     *     {@code outcome} is not {@link RecordedOutcome.Escalated}
     */
    static void requireEscalatedFor(
            String taskId, @Nullable RecordedOutcome outcome, Position position, @Nullable String decision) {
        if (decision == null) {
            return;
        }
        String conflict = position instanceof Position.AwaitingApproval(String gate)
                ? "awaiting approval after stage '" + gate + "'"
                : outcomeConflict(outcome);
        if (conflict != null) {
            throw new UsageException("--decision answers a recorded escalation, but task \"" + taskId + "\" is "
                    + conflict + " — resume it without --decision");
        }
    }

    private static @Nullable String outcomeConflict(@Nullable RecordedOutcome outcome) {
        return switch (outcome) {
            case null -> "an interrupted run with no recorded outcome";
            case RecordedOutcome.Escalated ignored -> null;
            case RecordedOutcome.Completed ignored -> "completed";
            case RecordedOutcome.Paused paused ->
                "paused at a manual checkpoint after stage '" + paused.passedStage() + "'";
            case RecordedOutcome.Aborted ignored -> "aborted";
        };
    }
}
