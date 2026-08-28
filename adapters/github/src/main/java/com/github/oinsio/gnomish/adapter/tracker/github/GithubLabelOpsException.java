package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException;

/**
 * Thrown by {@link GithubLabelOps} when a label mutation call returns an
 * error response that is not the tolerated "already absent" 404 on remove
 * (FR5 of add-tracker-port).
 *
 * <p>Extends {@link TrackerUnavailableException} (FR18 of harden-task-branch-contract): the
 * transient failures {@link com.github.oinsio.gnomish.adapter.github.GithubHttpClient} retries are
 * already exhausted by the time this surfaces, and a label flip is the index half of a write whose
 * truth marker has usually already landed — so a bounded terminal-write retry must consume it like
 * any other outage instead of failing the whole transition terminally with a retry budget left
 * unspent.
 *
 * <p>Implements FR5 of add-tracker-port; FR18 of harden-task-branch-contract.
 */
public final class GithubLabelOpsException extends TrackerUnavailableException {

    GithubLabelOpsException(String message) {
        super(message);
    }
}
