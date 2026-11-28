package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.domain.engine.AttemptRecord;

/**
 * Formats the service commit-message shapes the git adapter uses across its round
 * commits, task-lifecycle commits, salvage commit, cleanup commit, and
 * tracker-write-confirmed commit — the scheme fixed by design D14 and proposal Q1: {@code
 * gnomish: round <stage>#<n>}, {@code gnomish: task <event>}, {@code gnomish: salvage}, {@code
 * gnomish: cleanup}, {@code gnomish: task write-confirmed}. These are a human/audit-trail aid, not a parsing contract — {@code usage} reconstruction walks
 * {@code state.json} history instead (D14) — so this class only has to get the text
 * right and consistent, not parseable. The snapshot, pending-round snapshot, unverified snapshot
 * and pending subjects, which <em>are</em> a parsing contract, are {@link RoundSubject}'s: writer and
 * parser of that wire form in one owner.
 *
 * <p>Pure string formatting: no git subprocess calls, no filesystem I/O. Callers such as
 * the git {@code AttemptPersistence} and {@code TaskRepository} adapters pass these
 * strings as the {@code git commit -m} message.
 *
 * <p>Implements FR2 of add-git-workflow (design D14); FR14 of define-executor-contract.
 */
public final class ServiceCommitMessages {

    /** The service namespace every factory subject opens with; {@link RoundSubject} prefixes its own with it. */
    static final String PREFIX = "gnomish: ";

    private ServiceCommitMessages() {}

    /**
     * The round commit message, {@code gnomish: round <stage>#<round>}, rendered from the recorded
     * round itself (FR14 of define-executor-contract, design D13): the record's {@code stage} — the
     * stage that ran the round, not the stage a same-commit advance moved the position to — and its
     * {@code round} as recorded, the attempt key's non-negative number. The subject and the record
     * cannot spell different stages, since the subject has no other source.
     *
     * @param record the round this commit records; its {@code stage} and {@code round} are the
     *     subject's
     * @return the formatted commit message
     */
    public static String round(AttemptRecord record) {
        return PREFIX + "round " + record.stage() + "#" + record.round();
    }

    /**
     * The task-lifecycle commit message: {@code gnomish: task <event>}.
     *
     * @param event the lifecycle write this commit records
     * @return the formatted commit message
     */
    public static String taskEvent(TaskLifecycleEvent event) {
        return PREFIX + "task " + eventName(event);
    }

    /**
     * The salvage commit message: fixed, no parameters (FR10).
     *
     * @return {@code "gnomish: salvage"}
     */
    public static String salvage() {
        return PREFIX + "salvage";
    }

    /**
     * The cleanup commit message: fixed, no parameters (FR15).
     *
     * @return {@code "gnomish: cleanup"}
     */
    public static String cleanup() {
        return PREFIX + "cleanup";
    }

    /**
     * The tracker-write-confirmed commit message: fixed, no parameters (FR10 of
     * add-claim-heartbeat). Written when a terminal park's tracker write has landed and the
     * durable "tracker-write pending" marker is cleared from {@code task.json}.
     *
     * @return {@code "gnomish: task write-confirmed"}
     */
    public static String trackerWriteConfirmed() {
        return PREFIX + "task write-confirmed";
    }

    private static String eventName(TaskLifecycleEvent event) {
        return switch (event) {
            case STARTED -> "started";
            case RESUMED -> "resumed";
            case APPROVED -> "approved";
            case COMPLETED -> "completed";
            case PAUSED -> "paused";
            case ESCALATED -> "escalated";
            case ABORTED -> "aborted";
        };
    }
}
