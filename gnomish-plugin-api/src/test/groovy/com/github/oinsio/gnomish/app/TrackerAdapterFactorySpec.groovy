package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * TrackerAdapterFactory's epoch-aware creation seam (FR13 of
 * harden-task-branch-contract): the composition root always calls the
 * four-argument form, and an adapter that does not stamp epochs is still built
 * by it — epoch stamping is adapter-optional, so the default routes to the
 * three-argument form and returns exactly what it built.
 *
 * FR13: the tenure record reaches the adapters that stamp it, without forcing
 * every adapter to take one.
 */
class TrackerAdapterFactorySpec extends Specification {

    private static TrackerConfig config() {
        new TrackerConfig('stand-in', 3, java.time.Duration.ofMinutes(5), 3, 1, Map.of())
    }

    /** An adapter that implements only the three-argument form — the "does not stamp epochs" case. */
    private static class EpochUnawareFactory implements TrackerAdapterFactory {

        final Tracker built

        EpochUnawareFactory(Tracker built) {
            this.built = built
        }

        @Override
        String type() {
            'stand-in'
        }

        @Override
        Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
            built
        }

        @Override
        TaskRef expandRef(TrackerConfig config, String rawRef) {
            new TaskRef('stand-in:' + rawRef)
        }
    }

    def "the default four-argument create builds through the three-argument form and returns its tracker"() {
        given:
        def factory = new EpochUnawareFactory(Stub(Tracker))

        when:
        def tracker = factory.create(Stub(SecretsProvider), config(), 'gnomish-factory-x7k2q1', ClaimEpochSource.NONE)

        then: 'the very tracker the adapter built — not null, not a substitute'
        tracker.is(factory.built)
    }

    def "an adapter that does not stamp epochs is built the same whichever form the caller uses"() {
        given:
        def factory = new EpochUnawareFactory(Stub(Tracker))
        def secrets = Stub(SecretsProvider)

        expect:
        factory.create(secrets, config(), 'gnomish-factory-x7k2q1', ClaimEpochSource.NONE)
                .is(factory.create(secrets, config(), 'gnomish-factory-x7k2q1'))
    }
}
