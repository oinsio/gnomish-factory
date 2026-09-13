package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.logtext.LogText;

/**
 * The report a resumed task parks with when its pinned base ref no longer resolves (FR12, design
 * D13 of add-base-ref-resolution): named ref, the resolution's own detail, and the standing
 * instruction — never a silent fall-back to the pinned SHA. Mirrors {@link BaseLawReport}'s shape
 * for the equivalent fresh-claim report, so an operator reads one report format wherever a base
 * resolution is refused.
 *
 * <p>Pure text assembly, no I/O: the tracker write and the log line are the caller's.
 *
 * <p>The pinned ref is read back from the task branch's {@code task.json} and the detail is built
 * around remote-supplied names, so both pass {@link LogText} here, where they enter the factory's
 * own text — the assembled report is logged whole and posted as a tracker comment, past the reach
 * of {@code UntrustedLogTextGateSpec} (FR6 of harden-logging-observability).
 *
 * <p>Implements FR12, D13 of add-base-ref-resolution.
 */
public final class ResumeBaseReport {

    private ResumeBaseReport() {}

    /**
     * Renders the report for a pinned ref that resolves nowhere.
     *
     * @param taskId the parked task; never null
     * @param pinnedRef the base ref the task's pin names; never null
     * @param detail the resolution's own account of what stood in the way; never blank
     * @return the operator-facing report; never blank
     */
    public static String unresolved(String taskId, String pinnedRef, String detail) {
        return "Task " + taskId + " is parked: its pinned base ref no longer resolves.\n"
                + "Pinned ref: " + LogText.forLog(pinnedRef) + "\n"
                + "Detail: " + LogText.forLog(detail) + "\n"
                + ParkedBaseTrailer.withRemedy(ParkedBaseTrailer.REPOINT_BASE);
    }
}
