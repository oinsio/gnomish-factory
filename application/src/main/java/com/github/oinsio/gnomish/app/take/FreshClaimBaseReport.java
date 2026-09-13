package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.baseref.UnderdeterminedCause;
import com.github.oinsio.gnomish.logtext.LogText;
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
 * <p>Every field this report carries from outside the factory — the designator values a tracker
 * held, the resolver's reason built around them, the resolved ref name — is passed through {@link
 * LogText} here, where it enters the factory's own text. It has to happen here rather than at the
 * sinks: the assembled report is logged whole by {@code SlotOutcomeLog} and posted as a tracker
 * comment, and by then no untrusted accessor is left at the call site for
 * {@code UntrustedLogTextGateSpec} to see. Same reasoning, same remedy as {@code
 * GitCommandResult.failureDetail} (FR6 of harden-logging-observability;
 * {@code .claude/rules/logging.md}).
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
     * @param values the designator values found on the task, empty for a cause that names none
     * @param reason the resolver's own account of what was found and what is allowed; never blank
     * @return the operator-facing report; never blank
     */
    public static String underdetermined(
            String taskId, UnderdeterminedCause cause, List<String> values, String reason) {
        String named = values.isEmpty()
                ? ""
                : "Values named: " + values.stream().map(LogText::forLog).collect(Collectors.joining(", ")) + "\n";
        return "Task " + taskId + " is parked: its base could not be determined.\n"
                + "Cause: " + cause + "\n"
                + named
                + "Detail: " + LogText.forLog(reason) + "\n"
                + ParkedBaseTrailer.withRemedy("Fix the task's base designator or the project's"
                        + " allowed bases, then return the task to work.");
    }

    /**
     * Renders the report for a resolved base ref that a refresh refused — a deterministic fact
     * about the ref itself (missing, diverging, ambiguous), never a silent fall-back.
     *
     * @param taskId the parked task; never null
     * @param ref the resolved base ref the refresh was attempted for; never null
     * @param detail the refresh's own account of what stood in the way; never blank
     * @return the operator-facing report; never blank
     */
    public static String refused(String taskId, String ref, String detail) {
        return "Task " + taskId + " is parked: its resolved base ref could not be refreshed.\n"
                + "Resolved ref: " + LogText.forLog(ref) + "\n"
                + "Detail: " + LogText.forLog(detail) + "\n"
                + ParkedBaseTrailer.withRemedy(ParkedBaseTrailer.REPOINT_BASE);
    }
}
