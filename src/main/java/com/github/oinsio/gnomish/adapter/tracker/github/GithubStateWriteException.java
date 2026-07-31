package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException;

/**
 * Thrown by {@link GithubStateWrites} and {@link GithubCorrespondence} when
 * a structural-comment POST needed by {@code park}, {@code finish}, {@code
 * recordAbort}, or {@code postNote} returns a non-2xx response outside the
 * Resilience4j retry budget already applied by {@link GithubHttpClient}
 * (FR14, FR18 of add-tracker-port).
 *
 * <p>Extends {@link TrackerUnavailableException} (FR10 of add-claim-heartbeat): the
 * transient 5xx/network failures {@link GithubHttpClient} already retries are exhausted
 * by the time this surfaces, so a write that fails this way is treated by core's
 * terminal-write retry as a (bounded, retryable) tracker outage rather than a
 * non-retryable fault — a finish/park kept durable in the branch is re-attempted until
 * the tracker returns or the bound elapses, then reconcile-on-resume closes the gap.
 *
 * <p>Implements FR14, FR18 of add-tracker-port; FR10 of add-claim-heartbeat.
 */
public final class GithubStateWriteException extends TrackerUnavailableException {

    GithubStateWriteException(String message) {
        super(message);
    }
}
