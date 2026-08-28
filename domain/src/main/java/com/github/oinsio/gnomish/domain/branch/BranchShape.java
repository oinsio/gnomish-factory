package com.github.oinsio.gnomish.domain.branch;

/**
 * The classification of a task branch tip: its file set, envelope versions, and claim epoch mapped
 * to exactly one name from a closed set of eleven. Sealed, so every reader switches without a
 * default branch and adding a shape fails the build until each reader names it (FR2).
 *
 * <p>The shapes and their meanings are owned by the {@code task-branch-contract} capability, in its
 * "Total branch-shape classification" requirement — this type realizes that table and does not
 * restate it. The recovery owner and roll-forward/discard disposition per shape are owned by {@code
 * docs/adr/0003-crash-consistency.md} and realized by {@link #recoveryOwner()} / {@link
 * #disposition()}, which keep the whole mapping readable in one place rather than scattered over
 * eleven bodies.
 *
 * <p>Implements FR1, FR2, FR15 of harden-task-branch-contract.
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
     * An outcome is recorded and a human is awaited. The pending-write marker is a sub-state of
     * this shape, not a shape of its own.
     */
    record Parked() implements BranchShape {}

    /** The human's decision is appended and the outcome cleared, so the branch is resumable. */
    record Answered() implements BranchShape {}

    /** An outcome is recorded and cleanup is still pending. */
    record CompletedUncleaned() implements BranchShape {}

    /** Cleanup completed — found in history, so commits made after cleanup do not hide it. */
    record Delivered() implements BranchShape {}

    /** The tip's artifacts carry a claim epoch older than the live claim. */
    record StaleEpoch() implements BranchShape {}

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
            case Created(), InProgress(), Answered() -> RecoveryOwner.STAGE_ENGINE;
            case Parked() -> RecoveryOwner.TERMINAL_TRANSITION;
            case CompletedUncleaned() -> RecoveryOwner.COMPLETION_FINISH;
            case Delivered() -> RecoveryOwner.NONE;
            case StaleEpoch() -> RecoveryOwner.REPLICA_RECONCILER;
            case UnsupportedVersion ignoredVersion -> RecoveryOwner.RECOVERY_BUDGET;
            case Corrupt ignoredCorrupt -> RecoveryOwner.RECOVERY_BUDGET;
            case Unknown ignoredUnknown -> RecoveryOwner.RECOVERY_BUDGET;
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
            case Bare(), Created(), InProgress(), Parked(), Answered(), CompletedUncleaned() ->
                RecoveryDisposition.ROLL_FORWARD;
            case Delivered() -> RecoveryDisposition.TERMINAL;
            case StaleEpoch() -> RecoveryDisposition.DISCARD;
            case UnsupportedVersion ignoredVersion -> RecoveryDisposition.QUARANTINE;
            case Corrupt ignoredCorrupt -> RecoveryDisposition.QUARANTINE;
            case Unknown ignoredUnknown -> RecoveryDisposition.QUARANTINE;
        };
    }

    /**
     * Whether the tip of a branch of this shape carries the {@code .gnomish-task/} envelopes an
     * inspector can render a report from (FR16). The three quarantine shapes carry nothing
     * trustworthy, a {@link Bare} branch carries nothing at all, and a {@link Delivered} branch had
     * its factory files stripped by the cleanup commit — for those four, inspection reports the
     * shape itself instead of a report built from files that are absent or unreadable.
     *
     * <p>One owner for the question: the branch reader and the branch lister both ask it, and two
     * copies of this table would be a manual synchronization pair with nothing to keep them in step
     * (`.claude/rules/manual-sync-pairs.md`).
     *
     * @return {@code true} when a tip of this shape is readable into a status report
     */
    default boolean tipCarriesState() {
        return switch (this) {
            case Created(), InProgress(), Parked(), Answered(), CompletedUncleaned(), StaleEpoch() -> true;
            case Bare(), Delivered() -> false;
            case UnsupportedVersion ignoredVersion -> false;
            case Corrupt ignoredCorrupt -> false;
            case Unknown ignoredUnknown -> false;
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
     * line (NFR-O1). Every other shape is a repair worth one structured log entry.
     *
     * @return {@code true} for the shapes a healthy progression passes through
     */
    default boolean isClean() {
        return switch (this) {
            case Created(), InProgress(), Answered(), Delivered() -> true;
            case Bare(), Parked(), CompletedUncleaned(), StaleEpoch() -> false;
            case UnsupportedVersion ignoredVersion -> false;
            case Corrupt ignoredCorrupt -> false;
            case Unknown ignoredUnknown -> false;
        };
    }
}
