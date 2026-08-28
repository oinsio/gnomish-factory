package com.github.oinsio.gnomish.adapter.tracker;

import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import com.github.oinsio.gnomish.app.lease.EpochRecordingTracker;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.List;
import java.util.Optional;

/**
 * Wraps a discovered provider so that every {@link Tracker} it builds keeps this instance's {@link
 * ClaimEpochBook} current (FR13 of harden-task-branch-contract). Applied once, over the whole
 * registry, at the composition root: a claim made through any command — {@code take}, {@code serve},
 * or one added later — is therefore recorded without that command having to remember to record it,
 * and no writer has to be handed the tenure by hand.
 *
 * <p>The book travels in both directions: the decorator records what a claim returns, and hands
 * the same book to the delegate as its {@link
 * com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource} so the adapter's own writers can
 * stamp the tenure they are writing under (FR13). One record, filled at the single claim choke
 * point and read by every writer — never a second, adapter-local copy that could disagree with it.
 *
 * <p>Everything except {@link #create} is plain delegation: the provider decides its type, ref
 * expansion, foreign-ref refusal, subsection validation, and credential names, and this decorator
 * has no opinion on any of them.
 *
 * <p>Implements FR13 of harden-task-branch-contract.
 */
record EpochRecordingTrackerFactory(TrackerAdapterFactory delegate, ClaimEpochBook book)
        implements TrackerAdapterFactory {

    @Override
    public String type() {
        return delegate.type();
    }

    @Override
    public Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
        return new EpochRecordingTracker(delegate.create(secrets, config, instanceId, book), book);
    }

    @Override
    public TaskRef expandRef(TrackerConfig config, String rawRef) {
        return delegate.expandRef(config, rawRef);
    }

    @Override
    public Optional<String> refuseForeignRef(SecretsProvider secrets, TrackerConfig config, TaskRef ref) {
        return delegate.refuseForeignRef(secrets, config, ref);
    }

    @Override
    public Optional<TrackerSubsectionValidator> subsectionValidator() {
        return delegate.subsectionValidator();
    }

    @Override
    public List<String> credentialEnvVars(TrackerConfig config) {
        return delegate.credentialEnvVars(config);
    }
}
