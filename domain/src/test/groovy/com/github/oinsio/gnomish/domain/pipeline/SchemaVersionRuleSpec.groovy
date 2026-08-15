package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * SchemaVersionRule: the pure schema-version check (design D6) over the
 * schemaVersion the model carries from config.yaml. Contract: the adapter
 * mapper (task 5.3) hands a schemaVersion missing from config.yaml over as a
 * blank string — PipelineDefinition carries a non-null String under
 * NullMarked, so absence is represented as blankness, never null. A blank or
 * unsupported version yields exactly one located ConfigError naming
 * config.yaml and the schemaVersion field (NFR-O1, UX2); the single supported
 * version is '1' (design risk note: schemaVersion churn).
 * Implements FR9 of load-pipeline-config.
 */
class SchemaVersionRuleSpec extends Specification {

    // FR9/design risk note: one supported version, pinned as a constant the
    // fixtures and the adapter align with
    def "the single supported schema version is '1'"() {
        expect:
        SchemaVersionRule.SUPPORTED_VERSION == '1'
    }

    // FR9: the supported version is the only accepted value — no errors
    def "the supported schema version produces no errors"() {
        expect: 'validating the supported version yields an empty error list'
        SchemaVersionRule.validate(SchemaVersionRule.SUPPORTED_VERSION) == []
    }

    // FR9 delta-spec scenario: missing schemaVersion (mapper contract: blank
    // string) → located error naming config.yaml and the supported version
    def "a missing (blank '#version') schema version is a located error naming config.yaml"() {
        expect: 'exactly one error locating config.yaml: schemaVersion, naming the supported version'
        SchemaVersionRule.validate(version) == [
            new ConfigError('config.yaml', 'schemaVersion',
            "missing required schemaVersion; supported version is '1'")
        ]

        where:
        version << ['', '   ', '\t\n']
    }

    // FR9 delta-spec scenario: unknown/unsupported schemaVersion → located
    // error naming the offending version and the supported one (UX2)
    def "unknown schema version '#version' is a located error naming the version"() {
        expect: 'exactly one error naming the offending version and the supported one'
        SchemaVersionRule.validate(version) == [
            new ConfigError('config.yaml', 'schemaVersion',
            "unsupported schemaVersion '$version'; supported version is '1'" as String)
        ]

        where:
        version << [
            '2',
            '0',
            'weird',
            '1.0',
            ' 1'
        ]
    }
}
