package com.github.oinsio.gnomish.domain.branch;

/**
 * The classification of a task branch tip: its file set and envelope versions mapped to exactly one
 * name from a closed set of twelve. Sealed, so every reader switches without a default branch and
 * adding a shape fails the build until each reader names it (FR2).
 *
 * <p>The shapes and their meanings are owned by the {@code task-branch-contract} capability, in its
 * "Total branch-shape classification" requirement — this type realizes that table and does not
 * restate it. The recovery owner and roll-forward/discard disposition per shape are owned by {@code
 * docs/adr/0003-crash-consistency.md} and realized by {@link #recoveryOwner()} / {@link
 * #disposition()}, which keep the whole mapping readable in one place rather than scattered over
 * twelve bodies.
 *
 * <p>Implements FR1, FR2, FR15 of harden-task-branch-contract; FR1, FR2 of fix-claim-epoch-fence;
 * FR11 of make-checkpoint-gate-durable; FR10 of fix-terminal-receipt-convergence.
 */
public sealed interface BranchShape {

    /** The branch ref exists but carries no STARTED commit. */
    record Bare() implements BranchShape {}

    /**
     * The STARTED commit is present and no round has completed — including a pre-contract tip
     * carrying {@code task.json} without {@code state.json} (FR3).
     */
    record Created() implements BranchShape {}

    /** A run is underway: rounds recorded, no outcome. */
    record InProgress() implements BranchShape {}

    /**
     * A {@code manual} stage passed and its gate is not opened: the recorded position is {@code
     * AwaitingApproval(stage)}. Whether the park outcome is also recorded is a sub-state of this
     * shape, not a shape of its own — the owner re-delivers a lost park and never runs a stage
     * (design D8 of make-checkpoint-gate-durable). Named like the position because the shape
     * <em>is</em> the position.
     *
     * <p>Implements FR11 of make-checkpoint-gate-durable.
     */
    record AwaitingApproval() implements BranchShape {}

    /**
     * An outcome is recorded at a non-gate position and a human is awaited. The pending-write
     * marker is a sub-state of this shape, not a shape of its own.
     */
    record Parked() implements BranchShape {}

    /** The human's decision is appended and the outcome cleared, so the branch is resumable. */
    record Answered() implements BranchShape {}

    /** An outcome is recorded and cleanup is still pending. */
    record CompletedUncleaned() implements BranchShape {}

    /** Cleanup completed — found in history, so commits made after cleanup do not hide it. */
    record Delivered() implements BranchShape {}

    /**
     * An envelope declares a version this factory does not support — its own shape, never a flavour
     * of {@link Corrupt}, so the diagnosis names the version instead of a parse failure.
     *
     * @param fileName the envelope that declared it, e.g. {@code "state.json"}
     * @param observedVersion the version found on the wire, or {@code -1} when the field was absent
     * @param supportedVersion the version this factory supports
     */
    record UnsupportedVersion(String fileName, int observedVersion, int supportedVersion) implements BranchShape {}

    /**
     * The tip carries a factory envelope and the clone holds no register for the task — as an
     * operator's or an IDE's {@code git fetch}, or a {@code git clone}, leaves a task branch. A state
     * of the clone, not of the branch: nothing is read at a register that is not there, and the
     * touchpoint fetch, which lands the register beside the branch, is what converges it (design D13
     * of fix-terminal-receipt-convergence). Never {@link Unknown}, which is a present and diverged
     * pair, and never {@link Bare}, which carries no envelope.
     *
     * <p>Implements FR10 of fix-terminal-receipt-convergence.
     *
     * @param register the register's ref name, which resolved to nothing in this clone
     * @param tip the branch tip's id
     */
    record Unregistered(String register, String tip) implements BranchShape {}

    /**
     * Content is unreadable or self-contradictory.
     *
     * @param reason names the offending file and the observed versus expected content
     */
    record Corrupt(String reason) implements BranchShape {}

    /**
     * A legal-but-unrecognized combination — the shape that keeps the classification total.
     *
     * @param reason what combination was observed, for the quarantine diagnosis
     */
    record Unknown(String reason) implements BranchShape {}

    /**
     * The one component responsible for converging this shape to a clean state.
     *
     * @return this shape's recovery owner; never null
     */
    default RecoveryOwner recoveryOwner() {
        return switch (this) {
            case Bare() -> RecoveryOwner.TAKE_ROUTING;
            case Created(), InProgress(), AwaitingApproval(), Answered() -> RecoveryOwner.STAGE_ENGINE;
            case Parked() -> RecoveryOwner.TERMINAL_TRANSITION;
            case CompletedUncleaned() -> RecoveryOwner.COMPLETION_FINISH;
            case Delivered() -> RecoveryOwner.NONE;
            case Unregistered _ -> RecoveryOwner.TOUCHPOINT_FETCH;
            case UnsupportedVersion _, Corrupt _, Unknown _ -> RecoveryOwner.RECOVERY_BUDGET;
        };
    }

    /**
     * What this shape's owner does with the frozen state: complete it, discard it, quarantine it,
     * or nothing.
     *
     * @return this shape's recovery disposition; never null
     */
    default RecoveryDisposition disposition() {
        return switch (this) {
            case Bare(), Created(), InProgress(), AwaitingApproval(), Parked(), Answered(), CompletedUncleaned() ->
                RecoveryDisposition.ROLL_FORWARD;
            case Delivered() -> RecoveryDisposition.TERMINAL;
            // What a reader does after its own read: a branch still Unregistered then has no
            // register to roll forward from (design D13 of fix-terminal-receipt-convergence).
            case Unregistered _, UnsupportedVersion _, Corrupt _, Unknown _ -> RecoveryDisposition.QUARANTINE;
        };
    }

    /**
     * Whether the tip of a branch of this shape carries the {@code .gnomish-task/} envelopes an
     * inspector can render a report from (FR16). The four quarantine shapes carry nothing
     * trustworthy — an {@link Unregistered} tip has no register to read the envelope at — a {@link
     * Bare} branch carries nothing at all, and a {@link Delivered} branch had its factory files
     * stripped by the cleanup commit — for those six, inspection reports the shape itself instead of
     * a report built from files that are absent or unreadable.
     *
     * <p>One owner for the question: the branch reader and the branch lister both ask it, and two
     * copies of this table would be a manual synchronization pair with nothing to keep them in step
     * (`.claude/rules/manual-sync-pairs.md`).
     *
     * @return {@code true} when a tip of this shape is readable into a status report
     */
    default boolean tipCarriesState() {
        return switch (this) {
            case Created(), InProgress(), AwaitingApproval(), Parked(), Answered(), CompletedUncleaned() -> true;
            case Bare(), Delivered() -> false;
            case Unregistered _, UnsupportedVersion _, Corrupt _, Unknown _ -> false;
        };
    }

    /**
     * Whether the {@code serve} janitor's branch pass hands a branch of this shape to the claimless
     * owner — the one table of that question (design D5, D13 of fix-terminal-receipt-convergence).
     * Two shapes qualify: {@link CompletedUncleaned}, whose cleanup is owed, and {@link
     * Unregistered}, which only the owner's touchpoint fetch can classify by its content. Every
     * other shape is left alone: its owner is a claimed run, or it has nothing left to converge.
     *
     * <p>Implements FR10 of fix-terminal-receipt-convergence.
     *
     * @return {@code true} when the branch pass forwards a branch of this shape
     */
    default boolean forwardedByBranchPass() {
        return switch (this) {
            case CompletedUncleaned(), Unregistered _ -> true;
            case Bare(), Created(), InProgress(), AwaitingApproval(), Parked(), Answered(), Delivered() -> false;
            case UnsupportedVersion _, Corrupt _, Unknown _ -> false;
        };
    }

    /**
     * This shape's name as a diagnosis, a log line or a table cell shows it — the record's own
     * simple name, so the closed set names itself exactly once (FR16, NFR-O1).
     *
     * @return the shape's name, e.g. {@code "Delivered"}; never blank
     */
    default String label() {
        return getClass().getSimpleName();
    }

    /**
     * Whether this is the shape a clean pickup expects — the classification that needs no repair
     * line (NFR-O1). Every other shape is a repair worth one structured log entry. A gate is clean:
     * it is where every {@code manual} stage stops, not a repair (FR11 of
     * make-checkpoint-gate-durable).
     *
     * @return {@code true} for the shapes a healthy progression passes through
     */
    default boolean isClean() {
        return switch (this) {
            case Created(), InProgress(), AwaitingApproval(), Answered(), Delivered() -> true;
            case Bare(), Parked(), CompletedUncleaned() -> false;
            case Unregistered _, UnsupportedVersion _, Corrupt _, Unknown _ -> false;
        };
    }
}
