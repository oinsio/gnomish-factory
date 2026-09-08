package com.github.oinsio.gnomish.app.port.pipeline

import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * FR3 of add-base-ref-resolution: {@link ConfiguredDesignatorKinds#fromRegistry} answers from the
 * registered adapter factory keyed by {@code tracker.type}, and reports no kinds for a type with no
 * registered factory — the same "located error elsewhere, not here" shape {@code TrackerSeamValidator}
 * uses for an unknown type.
 */
class ConfiguredDesignatorKindsSpec extends Specification {

    private static final class StubFactory implements TrackerAdapterFactory {
        private final Set<String> kinds

        StubFactory(Set<String> kinds) {
            this.kinds = kinds
        }

        @Override
        String type() {
            'stub'
        }

        @Override
        Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
            throw new UnsupportedOperationException()
        }

        @Override
        TaskRef expandRef(TrackerConfig config, String rawRef) {
            throw new UnsupportedOperationException()
        }

        @Override
        Set<String> configuredDesignatorKinds(TrackerConfig config) {
            kinds
        }
    }

    def "asks the registered factory for tracker.type, reporting the kinds it declares"() {
        given:
        def registry = ['stub': new StubFactory(['base'] as Set)]
        def config = new TrackerConfig('stub', 3)

        expect:
        ConfiguredDesignatorKinds.fromRegistry(registry).forTracker(config) == ['base'] as Set
    }

    def "an unregistered tracker.type reports no kinds — that mismatch is a located load error elsewhere"() {
        given:
        def registry = ['stub': new StubFactory(['base'] as Set)]
        def config = new TrackerConfig('unknown-type', 3)

        expect:
        ConfiguredDesignatorKinds.fromRegistry(registry).forTracker(config).isEmpty()
    }
}
