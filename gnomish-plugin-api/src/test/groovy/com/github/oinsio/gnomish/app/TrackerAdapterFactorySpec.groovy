package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.lang.reflect.Modifier
import java.time.Duration
import spock.lang.Specification

/**
 * TrackerAdapterFactory's creation seam (FR23, design D21 of
 * supervise-daemon-loops-and-embed-dashboard): one abstract {@code create} taking the host-built
 * {@link TrackerAdapterContext}, and no overload chain an implementor could override the wrong link
 * of. The context reaches the adapter whole.
 */
class TrackerAdapterFactorySpec extends Specification {

    private static TrackerConfig config() {
        new TrackerConfig('stand-in', 3, Duration.ofMinutes(5), 3, 1, Map.of())
    }

    /** The minimum an adapter implements: a discriminator, a tracker, a ref expansion. */
    private static class MinimalFactory implements TrackerAdapterFactory {

        final Tracker built
        TrackerAdapterContext received

        MinimalFactory(Tracker built) {
            this.built = built
        }

        @Override
        String type() {
            'stand-in'
        }

        @Override
        Tracker create(TrackerAdapterContext context) {
            received = context
            built
        }

        @Override
        TaskRef expandRef(TrackerConfig config, String rawRef) {
            new TaskRef('stand-in:' + rawRef)
        }
    }

    // FR23: one abstract create, taking the context, and no default overload delegating to another.
    def "FR23: the factory declares exactly one create, abstract, taking the context"() {
        when:
        def creates = TrackerAdapterFactory.declaredMethods.findAll {
            it.name == 'create'
        }

        then:
        creates.size() == 1
        creates[0].parameterTypes as List == [TrackerAdapterContext]
        Modifier.isAbstract(creates[0].modifiers)
        !creates[0].isDefault()
    }

    // FR23: the adapter receives the very context the host built — every collaborator in one value.
    def "FR23: create hands the adapter the host's context and returns its tracker"() {
        given:
        def factory = new MinimalFactory(Stub(Tracker))
        def context = FixedTrackerAdapterContext.of(config(), 'gnomish-factory-x7k2q1')

        when:
        def tracker = factory.create(context)

        then:
        tracker.is(factory.built)
        factory.received.is(context)
    }

    // FR3 of add-base-ref-resolution: an adapter that declares no extraction rule declares no
    //     designator kind, so the trusted-tier allowed-bases check has nothing to object to
    def "an adapter that declares no extraction rule reports no designator kinds"() {
        given:
        def factory = new MinimalFactory(Stub(Tracker))

        expect:
        factory.configuredDesignatorKinds(config()).isEmpty()
    }
}
