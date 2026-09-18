package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.baseref.UnderdeterminedCause;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The report a fresh claim parks with when its base could not be determined or refreshed (FR2,
 * FR6, FR9 of add-base-ref-resolution): mirrors {@link BaseLawReport} and {@link
 * ResumeBaseReport}'s shape for the equivalent law-load and resume-rebind reports, so an operator
 * reads one report format wherever a base decision is refused.
 *
 * <p>Pure text assembly, no I/O: the tracker write and the log line are the caller's.
 *
 * <p>Every field this report carries from outside the factory arrives as an {@link UntrustedText}
 * and leaves it through {@link UntrustedText#forComment()} — the exit that matches this report's
 * consumer, since the assembled paragraph is posted as a tracker comment and logged whole (design
 * D6, D7 of type-untrusted-text). The per-field {@code LogText} calls this class carried before are
 * gone with the types: a fence tells the operator, and the next model reading the thread, which
 * words were the factory's and which were the remote's — which flattening to one capped line could
 * not. The factory's own instruction lines stay outside the fence, which is why the report is
 * assembled here rather than wrapped at {@code Tracker.park}.
 *
 * <p>The ref names it interpolates are not untrusted text: every ref entering the process is held
 * to {@code RefNameSyntax} first, so they are {@code String} by NG4 of type-untrusted-text.
 *
 * <p>Implements FR2, FR6, FR9 of add-base-ref-resolution.
 */
public final class FreshClaimBaseReport {

    private FreshClaimBaseReport() {}

    /**
     * Renders the report for a base that could not be determined at all — the two designator
     * causes {@link com.github.oinsio.gnomish.baseref.BaseRefResolver} refuses to guess past.
     *
     * @param taskId the parked task; never null
     * @param cause which of the resolver's causes stopped resolution; never null
     * @param values the values found on the task — a tracker's designator values, or the operator's
     *     own malformed {@code --base}; empty for a cause that names none
     * @param reason the resolver's own account of what was found and what is allowed; never blank.
     *     Factory-authored prose whose quoted values already entered it through the carrier's log
     *     exit, so it needs no exit of its own here
     * @return the operator-facing report; never blank
     */
    public static String underdetermined(
            String taskId, UnderdeterminedCause cause, List<UntrustedText> values, String reason) {
        String named = values.isEmpty()
                ? ""
                : "Values named:\n"
                        + values.stream().map(UntrustedText::forComment).collect(Collectors.joining("\n")) + "\n";
        return "Task " + taskId + " is parked: its base could not be determined.\n"
                + "Cause: " + cause + "\n"
                + named
                + "Detail: " + reason + "\n"
                + ParkedBaseTrailer.withRemedy("Fix the task's base designator or the project's"
                        + " allowed bases, then return the task to work.");
    }

    /**
     * Renders the report for a resolved base ref that a refresh refused — a deterministic fact
     * about the ref itself (missing, diverging, ambiguous), never a silent fall-back.
     *
     * @param taskId the parked task; never null
     * @param ref the resolved base ref the refresh was attempted for, already held to the ref-name
     *     grammar; never null
     * @param detail the refresh's own account of what stood in the way, quoting git; never blank
     * @return the operator-facing report; never blank
     */
    public static String refused(String taskId, String ref, UntrustedText detail) {
        return "Task " + taskId + " is parked: its resolved base ref could not be refreshed.\n"
                + "Resolved ref: " + ref + "\n"
                + "Detail:\n" + detail.forComment() + "\n"
                + ParkedBaseTrailer.withRemedy(ParkedBaseTrailer.REPOINT_BASE);
    }
}
