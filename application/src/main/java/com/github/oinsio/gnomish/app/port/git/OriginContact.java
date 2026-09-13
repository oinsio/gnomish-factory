package com.github.oinsio.gnomish.app.port.git;

/**
 * Whether one successful base read reached origin, or was served by the clone alone.
 *
 * <p>The fact is a <em>label on the path the adapter took</em>, never a reading of git's output
 * (design D2): each success-construction site already is the decision "did we go to the network",
 * so it states the answer at its own return. Nothing parses a message, and no extra invocation is
 * made to learn it (NFR-R1).
 *
 * <p>It exists because a success outcome answers "is the base bound", not "did origin answer", and
 * the remote outage gate needs the second question: a base pinned to a commit the clone already
 * holds, and a resume in a clone with no {@code origin} at all, bind with no round trip. Only
 * {@link #CONTACTED} resets the gate's grown probe interval and advances the remote's last
 * successful contact (FR2, FR3).
 *
 * <p>The fact is a closed two-valued type, never text: it carries no untrusted text and opens no
 * new sink (NFR-S1). It is also not durable — it is read within the run that produced it and
 * persisted nowhere.
 *
 * <p>Implements FR1, NFR-S1 of signal-outage-gate-on-origin-contact.
 */
public enum OriginContact {

    /** Origin answered: a narrow fetch of a branch or a tag, or a fetch-by-SHA, delivered the base. */
    CONTACTED,

    /** No round trip happened: the clone already held the object, or a resume bound from a local tip. */
    CLONE_ONLY
}
