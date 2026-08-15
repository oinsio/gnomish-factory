package com.github.oinsio.gnomish.adapter.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * TrackerDto round-trip: the config.yaml tracker block deserializes into the
 * adapter's DTO — the core type/abort-threshold keys, plus every other
 * top-level key captured generically as an uninterpreted subsection (FR17 of
 * add-tracker-port). Mapping to the pure domain (default+null contract) is
 * PipelineMapper's concern; seam validation of the captured subsection
 * (unknown type, missing/mismatched subsection) is task 3.2.
 * Implements FR17 of add-tracker-port.
 */
class TrackerDtoSpec extends Specification {

    private final ObjectMapper yaml = PipelineYaml.mapper()

    def "a tracker block deserializes type and the kebab-case abort-threshold"() {
        given:
        def body = '''\
            type: github
            abort-threshold: 5
            '''.stripIndent()

        when:
        def dto = yaml.readValue(body, TrackerDto)

        then:
        dto.type() == 'github'
        dto.abortThreshold() == 5
        dto.subsections() == [:]
    }

    def "a tracker block with no abort-threshold leaves it null"() {
        given:
        def body = 'type: github\n'

        when:
        def dto = yaml.readValue(body, TrackerDto)

        then:
        dto.type() == 'github'
        dto.abortThreshold() == null
    }

    // FR3 of add-claim-heartbeat: the kebab-case heartbeat keys bind directly to
    // the explicit DTO fields and are NOT swept into the subsections any-setter —
    // they are loader-owned protocol constants, not an adapter subsection
    def "a tracker block deserializes the kebab-case heartbeat keys as core fields"() {
        given:
        def body = '''\
            type: github
            heartbeat-interval: 5m
            heartbeat-ttl-multiplier: 4
            '''.stripIndent()

        when:
        def dto = yaml.readValue(body, TrackerDto)

        then:
        dto.heartbeatInterval() == '5m'
        dto.heartbeatTtlMultiplier() == 4
        dto.subsections() == [:]
    }

    // FR3 of add-claim-heartbeat: omitted heartbeat keys leave the DTO fields null
    // for the mapper to default (5 min / 3)
    def "a tracker block with no heartbeat keys leaves them null"() {
        given:
        def body = 'type: github\n'

        when:
        def dto = yaml.readValue(body, TrackerDto)

        then:
        dto.heartbeatInterval() == null
        dto.heartbeatTtlMultiplier() == null
    }

    // FR6 of add-factory-serve: the kebab-case wip-limit key binds directly to
    // the explicit DTO field and is NOT swept into the subsections any-setter —
    // it is a loader-owned protocol constant, not an adapter subsection
    def "a tracker block deserializes the kebab-case wip-limit as a core field"() {
        given:
        def body = '''\
            type: github
            wip-limit: 20
            '''.stripIndent()

        when:
        def dto = yaml.readValue(body, TrackerDto)

        then:
        dto.wipLimit() == 20
        dto.subsections() == [:]
    }

    // FR6 of add-factory-serve: an omitted wip-limit leaves the DTO field null
    // for the mapper to default (10)
    def "a tracker block with no wip-limit leaves it null"() {
        given:
        def body = 'type: github\n'

        when:
        def dto = yaml.readValue(body, TrackerDto)

        then:
        dto.wipLimit() == null
    }

    // The core loader stays adapter-agnostic (task 3.1 scope): any unrecognized
    // top-level key — the adapter-owned subsection named after `type` — is
    // captured raw rather than causing a hard Jackson parse failure, since
    // FAIL_ON_UNKNOWN_PROPERTIES is on by default (PipelineYaml).
    def "an adapter-owned subsection is captured raw without failing the parse"() {
        given:
        def body = '''\
            type: github
            abort-threshold: 3
            github:
              api-url: https://api.github.com
              repo: owner/repo
            '''.stripIndent()

        when:
        def dto = yaml.readValue(body, TrackerDto)

        then: 'the core keys map normally and the subsection is captured, untyped, by name'
        dto.type() == 'github'
        dto.abortThreshold() == 3
        dto.subsections().keySet() == ['github'] as Set
        (dto.subsections()['github'] as Map)['api-url'] == 'https://api.github.com'
        (dto.subsections()['github'] as Map)['repo'] == 'owner/repo'
    }
}
