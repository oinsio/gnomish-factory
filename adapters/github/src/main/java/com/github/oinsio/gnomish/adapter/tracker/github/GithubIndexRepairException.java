package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException;

/**
 * Thrown by {@link GithubIndexRepair} when a read or write of the repair sequence returns a non-2xx
 * response outside the retry budget {@link com.github.oinsio.gnomish.adapter.github.GithubHttpClient}
 * already applied.
 *
 * <p>Extends {@link TrackerUnavailableException} (FR18 of harden-task-branch-contract): a repair
 * that fails this way is a tracker outage, retryable on a later sweep tick, never a fault that
 * skips the caller's retry budget.
 *
 * <p>Implements FR19, FR18 of harden-task-branch-contract.
 */
public final class GithubIndexRepairException extends TrackerUnavailableException {

    GithubIndexRepairException(String message) {
        super(message);
    }
}
