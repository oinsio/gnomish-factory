package com.github.oinsio.gnomish.app.branch;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.domain.branch.BranchShape;

/**
 * The tracker-facing text of a quarantine: what was found on the branch, how much automatic
 * recovery the task has already consumed, and what a human is being asked to do (FR15, NFR-O2,
 * UX2 of harden-task-branch-contract). It is the whole report, readable without factory logs —
 * an operator who only ever sees the tracker thread still learns the shape, the offending file, and
 * that no crash loop is coming, because the park happened on the FIRST classification.
 *
 * <p>The attempts consumed are stated even though this quarantine spends none: they are the
 * difference between "this branch was born unreadable" and "this branch went unreadable after the
 * factory retried it four times", which is the first thing a diagnosis needs.
 *
 * <p>Implements FR15, NFR-O2, UX2 of harden-task-branch-contract.
 */
public final class BranchQuarantineReport {

    private BranchQuarantineReport() {}

    /**
     * Composes the {@code park(INFRA)} report text for a quarantined branch.
     *
     * @param taskId the quarantined task; never blank
     * @param shape the non-recoverable shape its branch classified to; never null
     * @param facts the unified recovery accounting as it stands, unchanged by this quarantine;
     *     never null
     * @return finished report text; never blank
     */
    public static String of(String taskId, BranchShape shape, AbortFacts facts) {
        return "Branch quarantined: the task branch for "
                + taskId
                + " classifies as "
                + BranchShapeDiagnosis.phrase(shape)
                + ", which no automatic recovery can converge — retrying it would read the same"
                + " branch again, so the factory stops on the first classification instead of"
                + " looping. Recovery attempts consumed so far: "
                + facts.count()
                + " ("
                + facts.crashCount()
                + " crashed runs, "
                + facts.recoveryCount()
                + " failed branch repairs); this quarantine spent none of them."
                + nextStep(shape);
    }

    /**
     * What the operator is asked to do, which is not the same question for every producer of a
     * quarantine (UX2 of fix-claim-epoch-fence). The three envelope diagnoses name a file on the
     * branch, so "inspect and fix it" is actionable. {@link BranchShape.Bare} names no file — it
     * carries no {@code .gnomish-task/} content at all, and it reaches a quarantine only because
     * the factory routed a fresh-claim shape into the resume table. Telling an operator to fix an
     * offending file there sends them looking for something that does not exist.
     */
    private static String nextStep(BranchShape shape) {
        if (shape instanceof BranchShape.Bare) {
            return " Next: this branch carries no .gnomish-task/ files to inspect — nothing is wrong"
                    + " with its content. Return the task to the ready state; if it quarantines"
                    + " again, report the run, because a branch in this state reaching recovery at"
                    + " all is a factory routing defect rather than a branch an operator can repair.";
        }
        return " Next: inspect the task branch's .gnomish-task/ files against the diagnosis above,"
                + " fix or remove the offending file, and return the task to the ready state.";
    }
}
