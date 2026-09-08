package com.github.oinsio.gnomish.app.port.git;

/**
 * What one base refresh established: the commit the task branch may start from, or which of the two
 * failure classes stopped it.
 *
 * <p>The split between {@link Refused} and {@link Unavailable} is the whole point of the type, and
 * it is drawn <em>by cause, never by the step that observed it</em> (FR9). A ref origin does not
 * hold, a local tag diverging from origin's, a remote that refuses fetch-by-SHA, and a name origin
 * holds as both a branch and a tag are deterministic facts: re-asking cannot change them, so they
 * park the task with a report and cost the daemon nothing. A remote that never answered is the
 * infrastructure class: it is retried, and when it stays silent it releases the claim and opens the
 * remote outage gate without burning a stage attempt.
 *
 * <p>Implements FR6, FR9 of add-base-ref-resolution.
 */
public sealed interface BaseRefreshOutcome {

    /**
     * The base is present and fresh; the task branch may be created from {@link #commit()}.
     *
     * @param ref the resolved base ref as the decision named it
     * @param commit the commit it resolves to, read back from the destination ref or the object
     *     itself — never from {@code FETCH_HEAD}
     * @param kind the namespace origin holds it in
     */
    record Refreshed(String ref, String commit, BaseRefKind kind) implements BaseRefreshOutcome {}

    /**
     * A deterministic refusal: the task parks with this report, no stage attempt burned, and no
     * amount of retrying would change the answer.
     *
     * @param report one operator-facing paragraph naming the ref and what stood in the way
     */
    record Refused(String report) implements BaseRefreshOutcome {}

    /**
     * The remote never answered, so the base's freshness could not be established. Fail-closed: no
     * branch is created. Retried under the adapter's bounded git retry; charged to the daemon.
     *
     * @param reason one sentence naming what the invocation did, credentials already scrubbed
     */
    record Unavailable(String reason) implements BaseRefreshOutcome {}
}
