package com.github.oinsio.gnomish.adapter.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * PipelineDto round-trip: pipeline.yaml deserializes into the adapter's DTO —
 * the explicit, ordered list of stage names that is the source of truth for
 * stage order (FR3). Order preservation matters: the list must come back in
 * declaration order. Emptiness/uniqueness are validator concerns, not the DTO's.
 * Implements FR1, FR3 (DTO shape), D2 of load-pipeline-config.
 */
class PipelineDtoSpec extends Specification {

    private final ObjectMapper yaml = PipelineYaml.mapper()

    def "pipeline.yaml deserializes into an ordered stage-name list"() {
        given: 'a pipeline.yaml body listing stages in order'
        def body = '''\
            stages:
              - plan
              - implement
              - review
            '''.stripIndent()

        when: 'it is read into the DTO'
        def dto = yaml.readValue(body, PipelineDto)

        then: 'the stage names come back in exactly the declared order'
        dto.stages() == ['plan', 'implement', 'review']
    }
}
