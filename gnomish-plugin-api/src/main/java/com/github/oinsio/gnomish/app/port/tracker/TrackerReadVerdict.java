package com.github.oinsio.gnomish.app.port.tracker;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Optional;

/**
 * The verdict of {@code TrackerAdapterFactory.probeRead}: what the adapter's own read-only probe
 * of the configured tracker binding answered — the first request the adapter's startup makes,
 * classified by the code startup refuses with, so a probe and a start cannot disagree on the
 * tracker (design D7 of add-doctor-command). Exactly one of four:
 *
 * <ul>
 *   <li>{@link NotApplicable} — the adapter has no remote binding to probe (the in-memory
 *       reference adapter), so no call was made;
 *   <li>{@link ReadConfirmed} — the probe read the binding; the tracker objects startup will create
 *       are named;
 *   <li>{@link Refused} — the tracker refused the probe: its status, the cause startup names for
 *       that status, and the permissions the tracker reports it would have accepted, when it
 *       reports them;
 *   <li>{@link Malformed} — the tracker answered with success but not with what the probe reads.
 * </ul>
 *
 * <p>A failure to obtain any answer (the network, an outage that outlasted the adapter's own
 * retry budget) is not a verdict: the probe throws, as the startup request would. Reading is all a
 * verdict confirms — write access is not verified (NG5 of add-doctor-command).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR3 of add-doctor-command.
 */
public sealed interface TrackerReadVerdict {

    /** The adapter has no remote binding to probe; no call was made. */
    record NotApplicable() implements TrackerReadVerdict {}

    /**
     * The probe read the configured binding.
     *
     * @param toProvision the names of the tracker objects the adapter's startup provisioning will
     *     create because the binding lacks them (for GitHub, the factory's labels); never null,
     *     empty when nothing is missing
     */
    record ReadConfirmed(List<String> toProvision) implements TrackerReadVerdict {

        public ReadConfirmed {
            toProvision = List.copyOf(toProvision);
        }
    }

    /**
     * The tracker refused the probe.
     *
     * @param statusCode the status the tracker answered with (the HTTP status for an HTTP tracker)
     * @param cause the adapter's wording of that status — the cause its startup refusal names;
     *     never blank
     * @param acceptedPermissions the permissions the tracker reports it would have accepted, as
     *     the tracker sent them (for GitHub, the {@code X-Accepted-GitHub-Permissions} header of a
     *     403), carried as tracker text; empty when the tracker reported none
     */
    record Refused(int statusCode, String cause, Optional<UntrustedText> acceptedPermissions)
            implements TrackerReadVerdict {}

    /**
     * The tracker answered with success, but not with what the probe reads.
     *
     * @param cause the adapter's wording of what could not be read — the cause its startup
     *     refusal names; never blank
     */
    record Malformed(String cause) implements TrackerReadVerdict {}
}
