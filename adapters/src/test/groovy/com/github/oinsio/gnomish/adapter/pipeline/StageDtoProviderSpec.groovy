package com.github.oinsio.gnomish.adapter.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * StageDto round-trip for the plugin capability: an external check names the check
 * provider that must serve it and carries the provider-owned params, which bind as
 * plain JDK types rather than a Jackson JsonNode (D5a) so the domain mapper stays
 * Jackson-free. Split from {@link StageDtoSpec}, which owns the eight stage-contract
 * sections and the four verify-check variants.
 * Implements FR7 of add-plugin-architecture.
 */
class StageDtoProviderSpec extends Specification {

    private final ObjectMapper yaml = PipelineYaml.mapper()

    // FR7 (add-plugin-architecture): the external check binds its provider
    // discriminator and the provider-owned params as a plain JDK map, never a
    // Jackson JsonNode (D5a)
    def "the external check binds its provider and params as a plain JDK map"() {
        given: 'a stage whose external check names a provider and its params'
        def stage = '''\
            purpose: p
            inputs:
              - kind: source
            outputs:
              - id: o
            executor:
              type: agent-cli
              model: m
            instructions: i.md
            verify:
              - type: external
                checkId: quality-gate
                provider: http
                params:
                  url: https://sonar.example/api/qualitygates
                  headers:
                    accept: application/json
                interval: 30s
                timeout: 15m
            advancement: auto
            '''.stripIndent()

        when: 'the stage is read'
        def external = yaml.readValue(stage, StageDto).verify()[0] as VerifyCheckDto.External

        then: 'the provider is exposed and the params bind as plain JDK types'
        external.provider() == 'http'
        external.params() instanceof Map
        external.params().get('url') == 'https://sonar.example/api/qualitygates'
        external.params().get('headers') == [accept: 'application/json']
    }
}
