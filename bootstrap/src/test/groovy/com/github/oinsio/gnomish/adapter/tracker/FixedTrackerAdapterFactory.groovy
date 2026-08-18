package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig

import java.util.function.Supplier

/**
 * The one {@link TrackerAdapterFactory} every {@code InMemoryTake*Spec} in this package hands to the
 * CLI under test: it ignores the credentials/config a real adapter would resolve and simply answers
 * with the tracker the spec already seeded.
 *
 * <p>The tracker arrives as a {@link Supplier} rather than a value so that a spec which swaps its
 * tracker for a decorator <em>after</em> the factory is built — {@link
 * InMemoryTakeLifecycleRevocationSpec#closeOnSecondFetch} does exactly that — still sees the live
 * instance, matching how {@code TakeCommand} resolves a {@link Tracker} at run time.
 *
 * <p>{@link #expandRef} throws: these fixtures pass canonical refs, so a call here means the run
 * took a path the fixture does not model rather than a ref that needs expanding.
 *
 * <p>Implements FR1, FR3 of add-tracker-port.
 */
class FixedTrackerAdapterFactory implements TrackerAdapterFactory {

    private final Supplier<Tracker> tracker

    FixedTrackerAdapterFactory(Supplier<Tracker> tracker) {
        this.tracker = tracker
    }

    @Override
    String type() {
        'github'
    }

    @Override
    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
        tracker.get()
    }

    @Override
    TaskRef expandRef(TrackerConfig config, String rawRef) {
        throw new UnsupportedOperationException('not used by this fixture: refs are already canonical')
    }
}
