package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;

/**
 * Formats the service commit-message shapes the git adapter uses across its round
 * commits, task-lifecycle commits, snapshot commit, salvage commit, cleanup commit, and
 * tracker-write-confirmed commit — the scheme fixed by design D14 and proposal Q1: {@code
 * gnomish: round <stage>#<n>}, {@code gnomish: task <event>}, {@code gnomish: snapshot
 * <stage>#<n>}, {@code gnomish: salvage}, {@code gnomish: cleanup}, {@code gnomish: task
 * write-confirmed}. Except for the snapshot message (see {@link #snapshot}), these are a
 * human/audit-trail aid, not a parsing contract — {@code usage} reconstruction walks
 * {@code state.json} history instead (D14) — so this class only has to get the text
 * right and consistent, not parseable.
 *
 * <p>Pure string formatting: no git subprocess calls, no filesystem I/O. Callers such as
 * the git {@code AttemptPersistence} and {@code TaskRepository} adapters pass these
 * strings as the {@code git commit -m} message.
 *
 * <p>Implements FR2 of add-git-workflow (design D14).
 */
public final class ServiceCommitMessages {

    private static final String PREFIX = "gnomish: ";

    private ServiceCommitMessages() {}

    /**
     * The round commit message: {@code gnomish: round <stage>#<round>}.
     *
     * @param stage the stage id the round belongs to; matches {@code
     *     StatePositionDto.AtStage#stage}
     * @param round the round's 1-based sequence number within the current stage visit;
     *     matches {@code StateAttemptDto#round}
     * @return the formatted commit message
     */
    public static String round(String stage, int round) {
        return PREFIX + "round " + stage + "#" + round;
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
     * The sandboxed snapshot commit message: {@code gnomish: snapshot <stage>#<round>} (FR21 of
     * add-sandbox-core, design D15). Unlike every other message here, this one <em>is</em> a
     * parsing contract: resume classifies a branch tip carrying it as
     * "snapshot-without-state — died during verification" ({@link SnapshotTipCheck}), which is
     * the only way to tell a factory snapshot from the gnome's own in-box commits (both carry
     * the in-box identity).
     *
     * @param stage the stage id the round belongs to
     * @param round the round's 1-based sequence number within the current stage visit
     * @return the formatted commit message
     */
    public static String snapshot(String stage, int round) {
        return PREFIX + "snapshot " + stage + "#" + round;
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
            case COMPLETED -> "completed";
            case PAUSED -> "paused";
            case ESCALATED -> "escalated";
            case ABORTED -> "aborted";
        };
    }
}
