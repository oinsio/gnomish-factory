package com.github.oinsio.gnomish.domain.branch;

import com.github.oinsio.gnomish.domain.engine.Position;
import org.jspecify.annotations.Nullable;

/**
 * The one place a task branch becomes a named shape (design D3): a total mapping of file set ×
 * envelope version — read at the task's registered commit, the tip contributing only its relation
 * to it (design D13 of tighten-spec-contract) — onto the closed set of {@link BranchShape}. Three media reach it through the
 * tip-reader seam, and all three get the same verdict — three access paths must not become three
 * classifiers.
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
 *   <li>the register first — a tip the registered commit is not an ancestor of is {@link
 *       BranchShape.Unknown} with a diagnosis naming the register and the tip, a branch with no
 *       register whose tip carries an envelope is {@link BranchShape.Unregistered} (a state of the
 *       clone, which the touchpoint fetch converges — design D13 of
 *       fix-terminal-receipt-convergence), and a branch with no register and no envelope is {@link
 *       BranchShape.Bare}; a tip at or above the register classifies by the registered commit alone,
 *       so a record the gnome wrote above it changes nothing (FR13, NFR-S2 of tighten-spec-contract);
 *   <li>then delivery — the registered commit is the cleanup commit — a delivered branch is finished, so a stray post-cleanup file never
 *       re-parks a task that is done;
 *   <li>then the envelope diagnoses (version before parse failure, so a version diagnosis can name
 *       the version);
 *   <li>then the gate — a recorded {@code AwaitingApproval} position classifies as {@link
 *       BranchShape.AwaitingApproval} whatever the outcome says, so a lost park, a landed park and
 *       a stale earlier outcome all name the same owed step (FR11 of make-checkpoint-gate-durable);
 *   <li>then the content progression.
 * </ol>
 *
 * <p>The claim epoch stamped on the tip is not an input: a tip written by an earlier tenure
 * classifies exactly as the same tip unstamped would, so a reclaim routes on content and resumes
 * (fix-claim-epoch-fence FR1, FR2).
 *
 * <p>Implements FR1, FR2, FR3, FR13, FR15, NFR-R2 of harden-task-branch-contract; FR1, FR2 of
 * fix-claim-epoch-fence; FR11 of make-checkpoint-gate-durable; FR13, NFR-S2 of
 * tighten-spec-contract; FR10 of fix-terminal-receipt-convergence.
 */
public final class BranchShapeClassifier {

    /**
     * The task envelope's path-tail, as a diagnosis names it. An alias of {@link
     * EnvelopePaths#TASK_FILE}, which owns the spelling for every module (design D8 of
     * fix-envelope-medium); kept here for its existing readers.
     */
    public static final String TASK_FILE = EnvelopePaths.TASK_FILE;

    /**
     * The state envelope's path-tail, as a diagnosis names it. An alias of {@link
     * EnvelopePaths#STATE_FILE}, on the same terms as {@link #TASK_FILE}.
     */
    public static final String STATE_FILE = EnvelopePaths.STATE_FILE;

    /**
     * Classifies one branch tip.
     *
     * @param facts everything read from the tip; never null
     * @return exactly one shape; never null, and never a thrown exception for any content
     */
    public BranchShape classify(BranchTipFacts facts) {
        BranchShape unregistered = unregistered(facts.tipRelation());
        if (unregistered != null) {
            return unregistered;
        }
        if (facts.registeredCleanup()) {
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
        if (facts.recordedPosition().orElse(null) instanceof Position.AwaitingApproval) {
            return new BranchShape.AwaitingApproval();
        }
        return progression(facts);
    }

    /**
     * The shape a tip that does not stand at or above its registered commit takes, or null when it
     * does. Nothing is read at such a tip but the presence of an envelope, so no shape is guessed
     * from content the factory never registered.
     */
    private static @Nullable BranchShape unregistered(TipRelation relation) {
        return switch (relation) {
            case TipRelation.AtRegister(), TipRelation.AboveRegister() -> null;
            case TipRelation.Diverged(String register, String tip) ->
                new BranchShape.Unknown(
                        "the factory register " + register + " is not an ancestor of the branch tip " + tip);
            case TipRelation.NoRegister(String register, String tip, boolean tipCarriesEnvelope) ->
                tipCarriesEnvelope ? new BranchShape.Unregistered(register, tip) : new BranchShape.Bare();
        };
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
