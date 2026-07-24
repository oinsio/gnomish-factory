package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * TrackerConfig: the core tracker keys carried on PipelineDefinition when the
 * config.yaml tracker section is present (design D5) — the type discriminator
 * and the abort-fuse threshold shared by all instances. Adapter-owned
 * subsection validation (task 3.2) is not this record's concern; it is inert
 * data like the rest of the domain model (design D3: validation is data).
 * Implements FR17 of add-tracker-port.
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
