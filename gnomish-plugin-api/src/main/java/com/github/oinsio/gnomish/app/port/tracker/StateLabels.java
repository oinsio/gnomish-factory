package com.github.oinsio.gnomish.app.port.tracker;

/**
 * The logical state labels a task currently wears, reported as raw presence facts (design D16).
 * The labels are the index the listing queries filter on — never the truth, which the boundary
 * markers carry — so more than one may be present at once: the claim sequence's own kill window
 * leaves a task wearing both {@code ready} and {@code working} until the ready label is removed.
 *
 * <p>{@code closed} is the tracker's own disposal fact (a closed GitHub issue), which outranks
 * every label in the classification.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR19 of harden-task-branch-contract.
 *
 * @param ready whether the ready label is present
 * @param working whether the working label is present
 * @param needsHuman whether the needs-human label is present
 * @param delivered whether the delivered label is present
 * @param closed whether the task itself is closed in the tracker
 */
public record StateLabels(boolean ready, boolean working, boolean needsHuman, boolean delivered, boolean closed) {

    /**
     * The labels of an open task carrying only the working label — the shape every claimed task
     * wears once its claim sequence has completed.
     *
     * @return the working-only label set; never null
     */
    public static StateLabels workingOnly() {
        return new StateLabels(false, true, false, false, false);
    }

    /**
     * The labels of an open task carrying only the ready label.
     *
     * @return the ready-only label set; never null
     */
    public static StateLabels readyOnly() {
        return new StateLabels(true, false, false, false, false);
    }

    /**
     * The labels of an open task carrying only the needs-human label.
     *
     * @return the needs-human-only label set; never null
     */
    public static StateLabels needsHumanOnly() {
        return new StateLabels(false, false, true, false, false);
    }

    /**
     * The labels of a task carrying only the delivered label.
     *
     * @return the delivered-only label set; never null
     */
    public static StateLabels deliveredOnly() {
        return new StateLabels(false, false, false, true, false);
    }
}
