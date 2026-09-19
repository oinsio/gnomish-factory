package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The report a resumed task parks with when its pinned base ref no longer resolves (FR12, design
 * D13 of add-base-ref-resolution): named ref, the resolution's own detail, and the standing
 * instruction — never a silent fall-back to the pinned SHA. Mirrors {@link BaseLawReport}'s shape
 * for the equivalent fresh-claim report, so an operator reads one report format wherever a base
 * resolution is refused.
 *
 * <p>Pure text assembly, no I/O: the tracker write and the log line are the caller's.
 *
 * <p>The detail is built around what the remote said, so it arrives as an {@link UntrustedText} and
 * leaves through the comment plane — the plane that matches this report's consumer, since the
 * paragraph is posted as a tracker comment (design D6, D7 of type-untrusted-text). It takes the
 * plane's <em>fenced</em> shape, {@link UntrustedText#forComment()}, and that is a decision the
 * 2026-09-19 revision of D6 leaves standing rather than one it predates: this report carries one
 * untrusted field under a heading of the factory's own, so the block the fence spans really is
 * machine output end to end and the label states something true about it. A report interleaving
 * several such fields takes {@link UntrustedText#forCommentInline()} instead — see {@link
 * BaseLawReport} and {@link FreshClaimBaseReport#underdetermined}. The pinned ref beside it is not
 * untrusted text — it is read back through {@code PinnedRefGate}, which holds it to
 * {@code RefNameSyntax} — so it is interpolated as it stands (NG4).
 *
 * <p>Implements FR12, D13 of add-base-ref-resolution.
 */
public final class ResumeBaseReport {

    private ResumeBaseReport() {}

    /**
     * Renders the report for a pinned ref that resolves nowhere.
     *
     * @param taskId the parked task; never null
     * @param pinnedRef the base ref the task's pin names, already held to the ref-name grammar;
     *     never null
     * @param detail the resolution's own account of what stood in the way; never blank
     * @return the operator-facing report; never blank
     */
    public static String unresolved(String taskId, String pinnedRef, UntrustedText detail) {
        return "Task " + taskId + " is parked: its pinned base ref no longer resolves.\n"
                + "Pinned ref: " + pinnedRef + "\n"
                + "Detail:\n" + detail.forComment() + "\n"
                + ParkedBaseTrailer.withRemedy(ParkedBaseTrailer.REPOINT_BASE);
    }
}
