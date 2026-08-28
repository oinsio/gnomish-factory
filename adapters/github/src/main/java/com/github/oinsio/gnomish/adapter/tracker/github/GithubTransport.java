package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubHttpException;
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException;

/**
 * Translates the shared HTTP core's transport failure into the port's retryable outage (FR18 of
 * harden-task-branch-contract). {@link GithubHttpException} is raised once Resilience4j has
 * exhausted its budget against a connection that never answered — an unreachable tracker by any
 * reading — but it is a plain {@link RuntimeException}, so a bounded terminal-write retry that only
 * retries {@link TrackerUnavailableException} would treat it as a non-retryable fault and give up
 * with its budget unspent. The tracker adapter's write paths run through here so that never
 * happens.
 *
 * <p>The translation lives here rather than in the shared HTTP core: that core also serves the
 * external-check adapter, where "the tracker is unavailable" would be the wrong statement to make.
 *
 * <p>Implements FR18 of harden-task-branch-contract.
 */
final class GithubTransport {

    private GithubTransport() {}

    /**
     * Runs one tracker write, translating an exhausted transport failure into a retryable outage.
     *
     * @param write the write to run; never null
     */
    static void run(Runnable write) {
        try {
            write.run();
        } catch (GithubHttpException transport) {
            throw new GithubTransportException(transport);
        }
    }
}
