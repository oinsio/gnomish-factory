package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.*
import java.time.Duration
import spock.lang.Specification

/**
 * {@code PipelineMapper} maps structurally-valid adapter DTOs into the pure domain
 * {@code PipelineDefinition} (task 5.3, design D2). It receives a {@code ConfigDto} and
 * the ordered {@code (stageName, StageDto)} entries — order already taken from
 * {@code pipeline.yaml} by the loader (task 6.5) — and produces a {@code Result} carrying
 * either the mapped definition or a list of located mapping errors.
 *
 * <p>This spec holds the two whole-mapping facts: one representative config mapping
 * field-by-field with both input kinds, all four verify-check types, opaque settings and
 * default+override resolution, in preserved order; and the wire → domain enum boundary
 * (FR5), whose switches are total because StructuralValidation (5.2) already guaranteed
 * presence and type. The rest of the contract is split by capability across the family,
 * over the shared {@link PipelineMapperFixtureSupport}:
 *
 * <ul>
 *   <li>{@link PipelineMapperDefaultsSpec} — attempt-limit resolution (FR7) and absent
 *       schemaVersion / stages / sections, which become data, never an exception (D3)
 *   <li>{@link PipelineMapperSettingsSpec} — the plain-JDK settings map and its defensive
 *       copy (FR11, D5a): no Jackson type crosses the boundary
 *   <li>{@link PipelineMapperNullContractSpec} — the null → blank-string / zero boundary
 *       (5.2 ↔ 5.3), so the domain rules (4.x) report what the mapper refuses to crash on
 *   <li>{@link PipelineMapperDurationSpec} — external interval/timeout parsing and the
 *       located errors a malformed duration or timeout-class produces (FR11)
 *   <li>{@link PipelineMapperSandboxSpec} — the sandbox block, verify-in and pin paths
 *   <li>{@link PipelineMapperTrackerSpec} and {@link PipelineMapperHeartbeatSpec} — the
 *       {@code tracker} section: thresholds, subsection, wip limit and heartbeat keys
 * </ul>
 *
 * <p>Implements FR5, FR7, FR11, D2, D5a of load-pipeline-config.
 */
class PipelineMapperSpec extends Specification implements PipelineMapperFixtureSupport {

    // A representative full mapping: schemaVersion, both input kinds, all four
    // verify-check types, opaque settings, default+override resolution, order
    def "maps a full config into a field-by-field equal PipelineDefinition"() {
        given: 'a config with a default attempt limit and two ordered stages'
        def cfg = config('1', 3)
        def plan = new StageDto(
                'Plan the work',
                [new ArtifactInputDto.Source()],
                [
                    new ArtifactOutputDto('plan-doc')
                ],
                new ExecutorDto('api', 'claude-sonnet-4-5', [temperature: 0]),
                'stages/plan/instructions.md',
                [
                    new VerifyCheckDto.Builtin('files_exist', [paths: ['plan.md']])
                ],
                null,
                'manual')
        def build = new StageDto(
                'Build it',
                [
                    new ArtifactInputDto.Internal('plan-doc')
                ],
                [
                    new ArtifactOutputDto('impl-diff')
                ],
                new ExecutorDto('agent-cli', 'claude-opus-4-1', null),
                'stages/build/instructions.md',
                [
                    new VerifyCheckDto.Command('./gradlew check'),
                    new VerifyCheckDto.External('ci/build', '30s', '15m', null),
                    new VerifyCheckDto.Judge('criteria.md', 'claude-opus-4-1', [maxTokens: 1000], 3),
                ] as List<VerifyCheckDto>,
                new AutonomyDto(5),
                'auto')

        when:
        def result = PipelineMapper.map(cfg, [
            entry('plan', plan),
            entry('build', build)
        ])

        then: 'no mapping errors and a definition is produced'
        result.errors().isEmpty()
        def definition = result.definition()
        definition != null

        and: 'schema version and default limits carried across'
        definition.schemaVersion() == '1'
        definition.defaultLimits() == new AutonomyLimits(3)

        and: 'stages preserved in pipeline order'
        definition.stages()*.name() == ['plan', 'build']

        and: 'the plan stage maps field-by-field'
        def s0 = definition.stages()[0]
        s0.purpose() == 'Plan the work'
        s0.inputs() == [new ArtifactInput.Source()]
        s0.outputs() == [
            new ArtifactOutput('plan-doc')
        ]
        s0.executor() == new StageDefinition.Executor(ExecutorType.API, 'claude-sonnet-4-5', [temperature: 0])
        s0.instructionsRef() == 'stages/plan/instructions.md'
        s0.verify() == [
            new VerifyCheck.Builtin('files_exist', [paths: ['plan.md']])
        ]
        s0.limits() == new AutonomyLimits(3) // no override → config default
        s0.advancement() == AdvancementMode.MANUAL

        and: 'the build stage maps field-by-field, including all check types and override'
        def s1 = definition.stages()[1]
        s1.inputs() == [
            new ArtifactInput.Internal('plan-doc')
        ]
        s1.outputs() == [
            new ArtifactOutput('impl-diff')
        ]
        s1.executor() == new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-opus-4-1', [:])
        s1.verify() == [
            new VerifyCheck.Command('./gradlew check'),
            new VerifyCheck.External(
            'ci/build', 'github', Duration.ofSeconds(30), Duration.ofMinutes(15), VerifyCheck.TimeoutClass.QUALITY),
            new VerifyCheck.Judge('criteria.md', 'claude-opus-4-1', [maxTokens: 1000], 3),
        ]
        s1.limits() == new AutonomyLimits(5) // override wins
        s1.advancement() == AdvancementMode.AUTO
    }

    // FR5 boundary: enum wire values map to the domain enums, both values each
    def "maps executor type #wireType to #domainType and advancement #wireAdv to #domainAdv"() {
        given:
        def stage = new StageDto('p', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto(wireType, 'm', null), 'i.md', null, null, wireAdv)

        when:
        def s = mappedStage(stage)

        then:
        s.executor().type() == domainType
        s.advancement() == domainAdv

        where:
        wireType | wireAdv || domainType | domainAdv
        'api' | 'auto' || ExecutorType.API | AdvancementMode.AUTO
        'agent-cli' | 'manual' || ExecutorType.AGENT_CLI | AdvancementMode.MANUAL
    }
}
