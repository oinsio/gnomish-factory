package com.github.oinsio.gnomish.domain.branch;

import org.jspecify.annotations.Nullable;

/**
 * The one place a task branch tip becomes a named shape (design D3): a total mapping of file set ×
 * envelope version × claim epoch onto the closed set of {@link BranchShape}. Three media reach it
 * through the tip-reader seam, and all three get the same verdict — three access paths must not
 * become three classifiers.
 *
 * <p>Total and non-throwing by construction: unreadable content is a {@link
 * EnvelopeStatus.Unreadable} fact that classifies to {@link BranchShape.Corrupt}, and a combination
 * this contract does not recognize classifies to {@link BranchShape.Unknown} — never a thrown
 * exception and never a closest match (FR1, NFR-R2). Only environment unavailability surfaces as an
 * error, and it does so before the facts are ever assembled.
 *
 * <p>The order the rules are applied in is itself contract:
 *
 * <ol>
 *   <li>the epoch fence first — a stale artifact is {@code StaleEpoch} whatever its content says;
 *   <li>delivery second — a delivered branch is finished, so a stray post-cleanup file never
 *       re-parks a task that is done;
 *   <li>then the envelope diagnoses (version before parse failure, so a version diagnosis can name
 *       the version);
 *   <li>then the content progression.
 * </ol>
 *
 * <p>Implements FR1, FR2, FR3, FR13, FR15, NFR-R2 of harden-task-branch-contract.
 */
public final class BranchShapeClassifier {

    /** The task envelope's path-tail, as a diagnosis names it. */
    public static final String TASK_FILE = "task.json";

    /** The state envelope's path-tail, as a diagnosis names it. */
    public static final String STATE_FILE = "state.json";

    /**
     * Classifies one branch tip.
     *
     * @param facts everything read from the tip; never null
     * @return exactly one shape; never null, and never a thrown exception for any content
     */
    public BranchShape classify(BranchTipFacts facts) {
        if (isStale(facts.tipEpoch(), facts.liveEpoch())) {
            return new BranchShape.StaleEpoch();
        }
        if (facts.cleanupCommitInHistory()) {
            return new BranchShape.Delivered();
        }
        BranchShape envelopeFault = envelopeFault(facts);
        if (envelopeFault != null) {
            return envelopeFault;
        }
        if (facts.taskEnvelope() instanceof EnvelopeStatus.Absent) {
            return facts.stateEnvelope() instanceof EnvelopeStatus.Absent
                    ? new BranchShape.Bare()
                    : new BranchShape.Unknown(STATE_FILE + " present without " + TASK_FILE);
        }
        return progression(facts);
    }

    private static boolean isStale(@Nullable ClaimEpoch tip, @Nullable ClaimEpoch live) {
        return tip != null && live != null && tip.isStaleAgainst(live);
    }

    /**
     * The version and parse diagnoses of both envelopes, task envelope first: identity comes before
     * state, so a tip whose {@code task.json} is the broken one says so.
     */
    private static @Nullable BranchShape envelopeFault(BranchTipFacts facts) {
        BranchShape taskFault = faultOf(TASK_FILE, facts.taskEnvelope());
        return taskFault != null ? taskFault : faultOf(STATE_FILE, facts.stateEnvelope());
    }

    private static @Nullable BranchShape faultOf(String fileName, EnvelopeStatus status) {
        return switch (status) {
            case EnvelopeStatus.Absent(), EnvelopeStatus.Parsed() -> null;
            case EnvelopeStatus.UnsupportedVersion version ->
                new BranchShape.UnsupportedVersion(fileName, version.observedVersion(), version.supportedVersion());
            case EnvelopeStatus.Unreadable unreadable -> new BranchShape.Corrupt(fileName + ": " + unreadable.reason());
        };
    }

    /**
     * The happy-path progression, once the tip is known readable and its {@code task.json} present.
     * A pre-contract tip — {@code task.json} without {@code state.json} — falls through to {@link
     * BranchShape.Created} and resumes the first stage from scratch (FR3).
     */
    private static BranchShape progression(BranchTipFacts facts) {
        return switch (facts.recordedOutcome()) {
            case COMPLETED -> new BranchShape.CompletedUncleaned();
            case PARKED -> new BranchShape.Parked();
            case NONE -> resumable(facts);
        };
    }

    /**
     * With no outcome recorded, the attempt history separates the three resumable shapes: a
     * decision with no round run since it is {@link BranchShape.Answered} (the decision commit
     * resets the attempt counter in the same commit), any recorded round is {@link
     * BranchShape.InProgress}, and neither is {@link BranchShape.Created}.
     */
    private static BranchShape resumable(BranchTipFacts facts) {
        if (facts.roundsRecorded()) {
            return new BranchShape.InProgress();
        }
        return facts.decisionsRecorded() ? new BranchShape.Answered() : new BranchShape.Created();
    }
}
