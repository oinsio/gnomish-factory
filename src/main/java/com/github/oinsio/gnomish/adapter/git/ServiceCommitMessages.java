package com.github.oinsio.gnomish.adapter.git;

/**
 * Formats the four service commit-message shapes the git adapter uses across its round
 * commits, task-lifecycle commits, salvage commit, and cleanup commit — the scheme fixed
 * by design D14 and proposal Q1: {@code gnomish: round <stage>#<n>}, {@code gnomish: task
 * <event>}, {@code gnomish: salvage}, {@code gnomish: cleanup}. These are a
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
