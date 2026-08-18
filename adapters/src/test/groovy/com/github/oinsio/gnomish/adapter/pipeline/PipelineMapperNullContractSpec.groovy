package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * The null → blank-string / zero boundary of {@code PipelineMapper} (design boundary
 * 5.2 ↔ 5.3 of load-pipeline-config): where a DTO field is null the mapper hands the
 * domain a blank string, an empty collection or 0, so the pure domain rules (4.x) report
 * the semantic problem instead of the mapper crashing. Structural presence is already
 * guaranteed by StructuralValidation (5.2), so these are defensive boundaries that the
 * real flow never reaches — pinned here because the mapper must not NPE on them.
 *
 * <p>Implements FR11, D2 of load-pipeline-config.
 */
class PipelineMapperNullContractSpec extends Specification implements PipelineMapperFixtureSupport {

    def "maps null purpose and instructions to blank strings"() {
        given:
        def stage = new StageDto(null, null, null,
                new ExecutorDto('api', 'm', null), null, null, null, 'auto')

        when:
        def s = mappedStage(stage)

        then:
        s.purpose() == ''
        s.instructionsRef() == ''
    }

    // A null executor is structurally a 5.2 error and never reaches the mapper in the
    // real flow; it maps to a default API executor with a blank model so StageSanityRule
    // (4.4) reports the blank model — the same null → blank contract, no NPE
    def "maps a null executor to a default API executor with a blank model"() {
        given:
        def stage = new StageDto('p', null, null, null, 'i.md', null, null, 'auto')

        when:
        def executor = mappedStage(stage).executor()

        then:
        executor.type() == ExecutorType.API
        executor.model() == ''
        executor.settings() == [:]
    }

    def "maps a null executor model to a blank string"() {
        given:
        def stage = new StageDto('p', null, null,
                new ExecutorDto('api', null, null), 'i.md',
                [
                    new VerifyCheckDto.Judge('c.md', null, null, 1)
                ], null, 'auto')

        when:
        def s = mappedStage(stage)

        then:
        s.executor().model() == ''
        (s.verify()[0] as VerifyCheck.Judge).model() == ''
    }

    // Null producerOutputId / output id / builtin name → blank strings so the graph rule (4.3) reports them
    def "maps null artifact ids and builtin name to blank strings"() {
        given:
        def stage = new StageDto('p',
                [
                    new ArtifactInputDto.Internal(null)
                ],
                [new ArtifactOutputDto(null)],
                new ExecutorDto('api', 'm', null), 'i.md',
                [
                    new VerifyCheckDto.Builtin(null, null)
                ], null, 'auto')

        when:
        def s = mappedStage(stage)

        then:
        s.inputs() == [
            new ArtifactInput.Internal('')
        ]
        s.outputs() == [new ArtifactOutput('')]
        (s.verify()[0] as VerifyCheck.Builtin).name() == ''
    }

    def "maps null command, criteria file and check id to blank strings"() {
        given:
        def stage = ioLessStage([
            new VerifyCheckDto.Command(null),
            new VerifyCheckDto.External(null, '1s', '1m', null),
            new VerifyCheckDto.Judge(null, 'jm', null, 1),
        ] as List<VerifyCheckDto>)

        when:
        def checks = mappedStage(stage).verify()

        then:
        (checks[0] as VerifyCheck.Command).command() == ''
        (checks[1] as VerifyCheck.External).checkId() == ''
        (checks[2] as VerifyCheck.Judge).criteriaFile() == ''
    }

    // Null votes → 0 so StageSanityRule (4.4) flags it (votes >= 1 and odd)
    def "maps null judge votes to zero for the domain rule to flag"() {
        given:
        def stage = ioLessStage([
            new VerifyCheckDto.Judge('c.md', 'jm', null, null)
        ])

        when:
        def judge = mappedStage(stage).verify()[0] as VerifyCheck.Judge

        then:
        judge.votes() == 0
    }
}
