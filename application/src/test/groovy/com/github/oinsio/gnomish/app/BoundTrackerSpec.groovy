package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * {@link BoundTracker}: the one value an invocation binds once its tracker is provisioned (design
 * D8 of collapse-composition-roots) — its one derived method and its one wither.
 *
 * Implements FR8 of collapse-composition-roots.
 */
class BoundTrackerSpec extends Specification implements RunChainFakes {

    private static final TrackerConfig CONFIG = new TrackerConfig('github', 3)

    // FR8: the credential names come from the bound adapter over the bound config — the one place
    // they are read, which TakeCommand and the serve root each spelled before.
    def "the credential names are the bound adapter's names for the bound config"() {
        given:
        def factory = Mock(TrackerAdapterFactory)
        def bound = new BoundTracker(pipeline(), DEFAULT_TRUSTED_BASE, CONFIG, factory, Stub(Tracker), INSTANCE)

        when:
        def names = bound.credentialEnvVars()

        then:
        1 * factory.credentialEnvVars(CONFIG) >> ['GNOMISH_GITHUB_TOKEN']
        names == ['GNOMISH_GITHUB_TOKEN']
    }

    // FR8, design D8 amendment b: the serve root derives its binding over the decorated tracker;
    // the copy replaces the tracker and nothing else.
    def "withTracker replaces the tracker and keeps every other member"() {
        given:
        def definition = pipeline()
        def factory = Stub(TrackerAdapterFactory)
        def raw = Stub(Tracker)
        def decorated = Stub(Tracker)
        def bound = new BoundTracker(definition, DEFAULT_TRUSTED_BASE, CONFIG, factory, raw, INSTANCE)

        when:
        def served = bound.withTracker(decorated)

        then:
        served.tracker().is(decorated)
        served.definition().is(definition)
        served.trustedBase().is(DEFAULT_TRUSTED_BASE)
        served.trackerConfig().is(CONFIG)
        served.factory().is(factory)
        served.instanceId().is(INSTANCE)

        and: 'the original binding is unchanged'
        bound.tracker().is(raw)
    }
}
