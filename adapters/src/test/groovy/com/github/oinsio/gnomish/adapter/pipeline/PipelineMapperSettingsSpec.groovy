package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * The opaque-settings contract of {@code PipelineMapper} (FR11, design D5a of
 * load-pipeline-config): executor and judge {@code settings} are plain-JDK
 * {@code Map<String, Object>} values that flow straight through to the domain — no
 * Jackson type crosses the boundary — copied defensively so a later mutation of the
 * source map cannot reach the mapped definition, and an absent block becomes an empty
 * map rather than {@code null}.
 *
 * <p>Implements FR11, D5a of load-pipeline-config.
 */
class PipelineMapperSettingsSpec extends Specification implements PipelineMapperFixtureSupport {

    def "carries executor and judge settings as plain-JDK maps, defensively copied"() {
        given: 'a mutable settings map handed to the DTO'
        def settings = [temperature: 0.2d, nested: [flags: [true, false]]]
        def stage = new StageDto('p', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto('api', 'm', settings), 'i.md',
        [
            new VerifyCheckDto.Judge('c.md', 'jm', settings, 1)
        ], null, 'auto')

        when:
        def definition = mapOne(stage).definition()

        then: 'the domain carries an equal map, not a Jackson node'
        def executor = definition.stages()[0].executor()
        executor.settings() == [temperature: 0.2d, nested: [flags: [true, false]]]
        (definition.stages()[0].verify()[0] as VerifyCheck.Judge).settings() == settings

        when: 'the source map is mutated after mapping'
        settings.put('mutated', 'after')

        then: 'the mapped copy is unaffected'
        executor.settings() == [temperature: 0.2d, nested: [flags: [true, false]]]
    }

    def "maps absent settings to an empty map"() {
        given: 'an executor and a builtin with no settings/params'
        def stage = ioLessStage([
            new VerifyCheckDto.Builtin('files_exist', null)
        ])

        when:
        def s = mappedStage(stage)

        then: 'executor and builtin params are empty, never null'
        s.executor().settings() == [:]
        (s.verify()[0] as VerifyCheck.Builtin).params() == [:]
    }
}
