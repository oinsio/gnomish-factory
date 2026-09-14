package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig

/**
 * Records the epochs a tracker actually issues, in claim order, so a lifecycle spec can assert a
 * branch stamp against the tenure token its own tracker minted rather than against a literal only
 * one adapter would produce (FR6 of fix-claim-epoch-fence).
 *
 * <p>Reads the epoch off the port's own {@link ClaimResult.Acquired} answer — the same fact the
 * production decorator records — so it stays adapter-agnostic: the base spec installs it over
 * whichever concrete factory a subclass seeds.
 */
class ClaimWatchingTrackerFactory implements TrackerAdapterFactory {

    private final TrackerAdapterFactory delegate
    /** Epochs in the order the tracker issued them; appended to by every acquired claim. */
    final List<ClaimEpoch> issuedEpochs = Collections.synchronizedList(new ArrayList<ClaimEpoch>())

    ClaimWatchingTrackerFactory(TrackerAdapterFactory delegate) {
        this.delegate = delegate
    }

    @Override
    String type() {
        delegate.type()
    }

    @Override
    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
        new ClaimWatchingTracker(delegate.create(secrets, config, instanceId), issuedEpochs)
    }

    @Override
    TaskRef expandRef(TrackerConfig config, String rawRef) {
        delegate.expandRef(config, rawRef)
    }
}

/** The {@link Tracker} half of {@link ClaimWatchingTrackerFactory}: claim is watched, the rest delegated. */
class ClaimWatchingTracker implements Tracker {

    @Delegate(excludes = ['claim'])
    private final Tracker delegate
    private final List<ClaimEpoch> issuedEpochs

    ClaimWatchingTracker(Tracker delegate, List<ClaimEpoch> issuedEpochs) {
        this.delegate = delegate
        this.issuedEpochs = issuedEpochs
    }

    @Override
    ClaimResult claim(TaskRef ref, String instanceId) {
        ClaimResult result = delegate.claim(ref, instanceId)
        if (result instanceof ClaimResult.Acquired) {
            issuedEpochs << (result as ClaimResult.Acquired).epoch()
        }
        result
    }
}
