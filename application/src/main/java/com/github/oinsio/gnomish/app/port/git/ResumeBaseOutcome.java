package com.github.oinsio.gnomish.app.port.git;

/**
 * What one resume-time base-ref resolution established: the current tip a resumed task rebinds its
 * law to, or which of the two failure classes stopped it (design D13 of add-base-ref-resolution).
 *
 * <p>Distinct from {@link BaseRefreshOutcome} because resume's rule differs from a fresh claim's: a
 * clone with no {@code origin} configured at all is not a refusal here — it binds from the ref's
 * local tip instead, since a resume with nothing to fetch from is a legitimate shape (unlike a fresh
 * claim, which always runs against a remote-backed clone). The split between {@link Refused} and
 * {@link Unavailable} is drawn the same way {@link BaseRefreshOutcome} draws it — by cause, never by
 * the step that observed it (FR9): a ref origin does not hold and a ref that resolves nowhere locally
 * either are deterministic facts that park the task with a report; a remote that is configured but
 * never answered is the infrastructure class, released rather than parked (D9's fresh-claim model,
 * mirrored for resume).
 *
 * <p>Implements FR12, D13 of add-base-ref-resolution.
 */
public sealed interface ResumeBaseOutcome {

    /**
     * The pinned ref's current tip was established — fetched from origin, or read locally when the
     * clone has no {@code origin} at all; the resumed task's law rebinds to {@link #commit()}.
     *
     * @param ref the pinned ref name as the task recorded it
     * @param commit the commit it resolves to
     */
    record Bound(String ref, String commit) implements ResumeBaseOutcome {}

    /**
     * A deterministic refusal: the pinned ref resolves nowhere the resolution looked — neither on a
     * configured origin nor, absent one, in the clone's own local refs. The task parks with this
     * report rather than falling back to the pinned SHA.
     *
     * @param report one operator-facing paragraph naming the ref and what stood in the way
     */
    record Refused(String report) implements ResumeBaseOutcome {}

    /**
     * A configured {@code origin} never answered the narrow fetch, so the ref's freshness could not
     * be established. The claim is released rather than the task parked — the same infrastructure
     * class D9 charges the daemon for, never the task.
     *
     * @param reason one sentence naming what the invocation did, credentials already scrubbed
     */
    record Unavailable(String reason) implements ResumeBaseOutcome {}
}
