package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import spock.lang.Specification

/**
 * The "absence is data, never an exception" half of the {@code PipelineMapper} contract
 * (FR7, design D3 of load-pipeline-config): an attempt limit resolves from the config
 * default and the per-stage override, a missing default resolving to 0 so that
 * {@code StageSanityRule} (task 4.4) flags it rather than the mapper throwing; and an
 * absent schemaVersion, stage list or stage section maps to a blank string / empty list
 * for the pure domain rules (4.x) to report.
 *
 * <p>Implements FR7, D3 of load-pipeline-config.
 */
class PipelineMapperDefaultsSpec extends Specification implements PipelineMapperFixtureSupport {

    def "resolves the attempt limit #expected from default #defaultLimit and override #override"() {
        given:
        def stage = new StageDto('p', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto('api', 'm', null), 'i.md', null,
        override == null ? null : new AutonomyDto(override), 'auto')

        when:
        def definition = PipelineMapper.map(config('1', defaultLimit), [entry('p', stage)]).definition()

        then: 'the resolved stage limit matches, no exception for a missing default'
        notThrown(Exception)
        definition.stages()[0].limits() == new AutonomyLimits(expected)
        definition.defaultLimits() == new AutonomyLimits(defaultLimit == null ? 0 : defaultLimit)

        where:
        defaultLimit | override || expected
        3 | 5 || 5 // override wins
        3 | null || 3 // default applies
        null | 5 || 5 // no default, override still wins
        null | null || 0 // both absent → 0 (StageSanityRule flags it)
    }

    // Missing schemaVersion → blank string (SchemaVersionRule 4.1 reports it)
    def "maps a null schemaVersion to a blank string for the domain rule to flag"() {
        when:
        def definition = PipelineMapper.map(config(null, 1), []).definition()

        then: 'the domain carries a blank version, never null'
        definition.schemaVersion() == ''
    }

    // Empty pipeline maps to an empty stage list (StageOrderRule 4.2 reports it)
    def "maps an empty stage list to an empty-stage definition"() {
        when:
        def definition = PipelineMapper.map(config('1', 1), []).definition()

        then:
        definition.stages() == []
    }

    def "maps absent input, output and verify sections to empty lists"() {
        given:
        def stage = ioLessStage(null)

        when:
        def s = mappedStage(stage)

        then:
        s.inputs() == []
        s.outputs() == []
        s.verify() == []
    }
}
