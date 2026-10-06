package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import org.jspecify.annotations.Nullable;

/**
 * The one check that a {@code --decision} meets a task that can take one (FR9 of
 * make-run-headless): a decision answers a recorded escalation, so a resume carrying one over any
 * other recorded outcome is a usage error naming the conflict — raised by both resume routers
 * ({@link GitResumeRunner}, {@link ContainerResumeRunner}) right after {@code task.json} is read
 * and before any branch write, so the branch is untouched when the operator reads the message.
 *
 * <p>Implements FR9 of make-run-headless.
 */
final class ResumeDecisionGuard {

    private ResumeDecisionGuard() {}

    /**
     * Refuses a decision over a non-escalated recorded outcome; a resume without a decision passes
     * whatever the outcome.
     *
     * @param taskId the resumed task, as the branch records it
     * @param outcome the branch's recorded outcome, {@code null} for an interrupted run
     * @param decision the operator's {@code --decision}, or {@code null} when none was given
     * @throws UsageException when {@code decision} is present and {@code outcome} is not {@link
     *     RecordedOutcome.Escalated}
     */
    static void requireEscalatedFor(String taskId, @Nullable RecordedOutcome outcome, @Nullable String decision) {
        if (decision == null) {
            return;
        }
        String conflict =
                switch (outcome) {
                    case null -> "an interrupted run with no recorded outcome";
                    case RecordedOutcome.Escalated ignored -> null;
                    case RecordedOutcome.Completed ignored -> "completed";
                    case RecordedOutcome.Paused paused ->
                        "paused at a manual checkpoint after stage '" + paused.passedStage() + "'";
                    case RecordedOutcome.Aborted ignored -> "aborted";
                };
        if (conflict != null) {
            throw new UsageException("--decision answers a recorded escalation, but task \"" + taskId + "\" is "
                    + conflict + " — resume it without --decision");
        }
    }
}
