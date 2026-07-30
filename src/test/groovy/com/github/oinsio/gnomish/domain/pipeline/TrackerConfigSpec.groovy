package com.github.oinsio.gnomish.domain.pipeline

import java.time.Duration
import spock.lang.Specification

/**
 * TrackerConfig: the core tracker keys carried on PipelineDefinition when the
 * config.yaml tracker section is present (design D5) — the type discriminator,
 * the abort-fuse threshold, and the heartbeat protocol constants (beat interval
 * and TTL multiplier) shared by all instances. Adapter-owned subsection
 * validation (task 3.2) is not this record's concern; it is inert data like the
 * rest of the domain model (design D3: validation is data).
 * Implements FR17 of add-tracker-port, FR3 of add-claim-heartbeat.
 */
class TrackerConfigSpec extends Specification {

    // FR17: the type and threshold are exposed exactly as given
    def "exposes type and abort threshold exactly"() {
        when:
        def config = new TrackerConfig('github', 5)

        then:
        config.type() == 'github'
        config.abortThreshold() == 5
    }

    // FR3 of add-claim-heartbeat: the five-arg canonical carries the heartbeat
    // constants through exactly as given
    def "exposes the heartbeat interval and TTL multiplier exactly"() {
        when:
        def config = new TrackerConfig('github', 5, Duration.ofMinutes(2), 4, [:])

        then:
        config.heartbeatInterval() == Duration.ofMinutes(2)
        config.heartbeatTtlMultiplier() == 4
    }

    // FR3 of add-claim-heartbeat, design D8: the convenience constructors default
    // the heartbeat constants to 5 minutes / 3 (a 15-minute TTL)
    def "the convenience constructors default the heartbeat constants to 5 minutes and 3"() {
        expect: 'both the two-arg and three-arg forms carry the D8 defaults'
        new TrackerConfig('github', 5).heartbeatInterval() == Duration.ofMinutes(5)
        new TrackerConfig('github', 5).heartbeatTtlMultiplier() == 3
        new TrackerConfig('github', 5, [k: 'v']).heartbeatInterval() == Duration.ofMinutes(5)
        new TrackerConfig('github', 5, [k: 'v']).heartbeatTtlMultiplier() == 3
    }

    // FR3 of add-claim-heartbeat: the public default constants pin the D8 values
    def "the public default constants are 5 minutes and 3"() {
        expect:
        TrackerConfig.DEFAULT_HEARTBEAT_INTERVAL == Duration.ofMinutes(5)
        TrackerConfig.DEFAULT_HEARTBEAT_TTL_MULTIPLIER == 3
    }

    // FR17 delta-spec scenario "Defaulted threshold": a present section with no
    // abort-threshold key resolves to 3 — this record only carries the already
    // resolved value; the mapper (task 3.1) applies the default
    def "configs with the same fields are equal values"() {
        expect:
        new TrackerConfig('github', 3) == new TrackerConfig('github', 3)
    }

    // FR9 of add-tracker-port (task 5.14): the two-arg convenience constructor keeps every
    // call site predating the subsection field compiling unchanged, defaulting to an empty map
    def "the two-arg convenience constructor defaults the subsection to an empty map"() {
        when:
        def config = new TrackerConfig('github', 5)

        then:
        config.subsection() == [:]
    }

    // FR9 of add-tracker-port: the three-arg constructor carries the raw, adapter-owned
    // subsection through exactly as given
    def "the three-arg constructor exposes the subsection exactly"() {
        given:
        def subsection = ['api-url': 'https://api.github.com', 'repo': 'acme/widgets']

        when:
        def config = new TrackerConfig('github', 5, subsection)

        then:
        config.subsection() == subsection
    }

    // FR9 of add-tracker-port: a null subsection (e.g. constructed outside Jackson binding)
    // defaults to an empty map rather than propagating null, mirroring TrackerDto's own
    // null-safety convention
    def "a null subsection defaults to an empty map"() {
        when:
        def config = new TrackerConfig('github', 5, null)

        then:
        config.subsection() == [:]
    }
}
