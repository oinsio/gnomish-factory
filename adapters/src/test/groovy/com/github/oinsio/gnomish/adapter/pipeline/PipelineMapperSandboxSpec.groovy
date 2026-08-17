package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * The sandbox half of the {@code PipelineMapper} contract (FR12/FR13/FR16 of
 * add-sandbox-core): the Mechanism {@code sandbox} block, a command's {@code verify-in},
 * and an external check's pin paths load into the typed domain model; when the manifest
 * declares none of them the documented defaults land instead; and an unknown
 * {@code verify-in} is a located mapping error that discards the definition, mirroring
 * the timeout-class contract in {@link PipelineMapperDurationSpec}.
 *
 * <p>Implements FR12, FR13, FR16 of add-sandbox-core.
 */
class PipelineMapperSandboxSpec extends Specification implements PipelineMapperFixtureSupport {

    def "maps sandbox declarations, verify-in, and external pin paths into the typed model"() {
        given: 'a stage declaring a sandbox, a fresh-box command, and a pinned external'
        def dto = new StageDto('Build it', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto('agent-cli', 'm', null, new SandboxDto(['docker-inside'], true, null)),
        'i.md',
        [
            new VerifyCheckDto.Command('./gradlew test', 'fresh-box'),
            new VerifyCheckDto.External('ci.yml', null, null, '30s', '5m', null, ['.github/workflows/ci.yml']),
        ] as List<VerifyCheckDto>,
        null, 'auto')

        when:
        def result = PipelineMapper.map(config('1', 3), [entry('build', dto)])

        then: 'no mapping errors and the declarations are typed onto the domain model'
        result.errors().isEmpty()
        def stage = result.definition().stages()[0]
        stage.executor().sandbox() == new Sandbox(['docker-inside'], true)
        (stage.verify()[0] as VerifyCheck.Command).verifyIn() == VerifyCheck.VerifyIn.FRESH_BOX
        (stage.verify()[1] as VerifyCheck.External).pinPaths() == ['.github/workflows/ci.yml']
    }

    // an absent sandbox maps to none(), an absent verify-in to same-box, absent pin paths to empty
    def "applies sandbox, verify-in, and pin-path defaults when the manifest declares none"() {
        given: 'a stage declaring no sandbox, a plain command, and an unpinned external'
        def dto = new StageDto('Build', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto('agent-cli', 'm', null),
        'i.md',
        [
            new VerifyCheckDto.Command('./gradlew test'),
            new VerifyCheckDto.External('ci.yml', '30s', '5m', null),
        ] as List<VerifyCheckDto>,
        null, 'auto')

        when:
        def result = PipelineMapper.map(config('1', 3), [entry('build', dto)])

        then: 'the defaults land on the typed model'
        result.errors().isEmpty()
        def stage = result.definition().stages()[0]
        stage.executor().sandbox() == Sandbox.none()
        (stage.verify()[0] as VerifyCheck.Command).verifyIn() == VerifyCheck.VerifyIn.SAME_BOX
        (stage.verify()[1] as VerifyCheck.External).pinPaths() == []
    }

    def "an unknown verify-in is a located mapping error"() {
        when:
        def result = PipelineMapper.map(config('1', 3), [
            entry('build', new StageDto('B', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
            new ExecutorDto('agent-cli', 'm', null), 'i.md',
            [
                new VerifyCheckDto.Command('x', 'somewhere-else')
            ] as List<VerifyCheckDto>,
            null, 'auto'))
        ])

        then: 'the definition is discarded and the error names the offending check'
        result.definition() == null
        result.errors().size() == 1
        with(result.errors()[0]) {
            file() == 'stages/build/stage.yaml'
            where() == 'verify[0].verifyIn'
            message() == "unknown verify-in 'somewhere-else'; use 'same-box' or 'fresh-box'"
        }
    }
}
