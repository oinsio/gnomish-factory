package com.github.oinsio.gnomish.adapter.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * ConfigDto round-trip: config.yaml deserializes into the adapter's annotated
 * DTO — schema version plus the pipeline-wide autonomy defaults (attempt limit).
 * The DTO is the wire-format shape only; mapping to the pure domain and the
 * default+override resolution are task 5.3.
 * Implements FR1, FR9 (DTO shape), D2 of load-pipeline-config.
 */
class ConfigDtoSpec extends Specification {

    private final ObjectMapper yaml = PipelineYaml.mapper()

    def "config.yaml deserializes into ConfigDto with schema version and autonomy default"() {
        given: 'a config.yaml body'
        def body = '''\
            schemaVersion: "1"
            autonomy:
              attemptLimit: 3
            '''.stripIndent()

        when: 'it is read into the DTO'
        def dto = yaml.readValue(body, ConfigDto)

        then: 'the schema version and the default attempt limit are exposed'
        dto.schemaVersion() == '1'
        dto.autonomy().attemptLimit() == 3
    }

    def "a config.yaml without an autonomy block leaves autonomy null"() {
        given: 'a config.yaml declaring only the schema version'
        def body = 'schemaVersion: "1"\n'

        when: 'it is read into the DTO'
        def dto = yaml.readValue(body, ConfigDto)

        then: 'the optional autonomy block is absent, not defaulted here'
        dto.schemaVersion() == '1'
        dto.autonomy() == null
    }
}
