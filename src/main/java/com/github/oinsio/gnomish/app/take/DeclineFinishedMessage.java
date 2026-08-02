package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;

/**
 * Composes the human-facing decline comment text for a {@code finished} task the tracker refused
 * to reopen (design D3, D4 of enforce-finish-terminality): core supplies the text, mirroring how
 * {@link AbortReportBuilder} supplies the {@code park} report, so every {@link
 * com.github.oinsio.gnomish.app.port.tracker.Tracker#declineFinished} call site — the observed-feed
 * sweep ({@code FinishedDecline}) and the explicit-mandate refusal ({@code TakeDisposition}) —
 * posts identical, UX1-compliant wording instead of each composing its own literal.
 *
 * <p>UX1 requires the comment to be self-explanatory to a human with no factory-internals
 * knowledge: it must state the task is already finished, that nothing more will happen on it, and
 * what to do instead (open a new task or bug referencing this one) — read as a status notice, not
 * an error or a crash.
 *
 * <p>Implements UX1 of enforce-finish-terminality.
 */
public final class DeclineFinishedMessage {

    private DeclineFinishedMessage() {}

    /**
     * Composes the decline comment for {@code ref}.
     *
     * @param ref the finished task being declined; never null
     * @return the finished comment text; never blank
     */
    public static String forTask(TaskRef ref) {
        return "Task "
                + ref.id()
                + " is already finished; nothing more will happen on it here. If further changes"
                + " are needed, please open a new task or bug that references "
                + ref.id()
                + ".";
    }
}
