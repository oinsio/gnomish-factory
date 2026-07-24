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
