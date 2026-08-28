package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubHttpException;
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException;

/**
 * A tracker write that never reached GitHub: the transport failed and the shared HTTP core's own
 * retry budget was exhausted. Carries the original {@link GithubHttpException} as its cause, and is
 * a {@link TrackerUnavailableException} so a bounded terminal-write retry consumes it like any
 * other outage rather than failing the transition terminally (FR18).
 *
 * <p>Implements FR18 of harden-task-branch-contract.
 */
public final class GithubTransportException extends TrackerUnavailableException {

    GithubTransportException(GithubHttpException cause) {
        super("Tracker write failed at the transport: " + cause.getMessage());
        initCause(cause);
    }
}
