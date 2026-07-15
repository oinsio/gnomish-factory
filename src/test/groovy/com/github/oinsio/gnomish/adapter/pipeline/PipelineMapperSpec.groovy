package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.*
import java.time.Duration
import spock.lang.Specification
/**
 * PipelineMapper maps structurally-valid adapter DTOs into the pure domain
 * PipelineDefinition (task 5.3, design D2). It receives a ConfigDto and the
 * ordered (stageName, StageDto) entries — order already taken from pipeline.yaml
 * by the loader (task 6.5) — and produces a Result carrying either the mapped
 * definition or a list of located mapping errors.
 *
 * Contracts settled by this task:
 *   - Attempt-limit resolution (FR7): AutonomyLimits.resolve(default, override).
 *     A missing config default maps to 0, so a stage with no override resolves
 *     to 0 which StageSanityRule (task 4.4) then flags as non-positive — the
 *     mapper never throws for a missing limit (design D3: validation is data).
 *   - Null → blank-string / empty (design boundary 5.2 ↔ 5.3): the mapper hands
 *     the domain a blank string / empty collection where a DTO field is null, so
 *     the pure domain rules (4.x) report the semantic problem rather than the
 *     mapper crashing. Structural presence/enum/type is already guaranteed by
 *     StructuralValidation (5.2), so the enum switches are total.
 *   - Duration parsing (FR11, deferred here by 5.1): external interval/timeout
 *     raw strings are parsed to java.time.Duration; a malformed string becomes a
 *     located ConfigError and the whole map fails (definition absent), so 6.5
 *     aggregates it into the one error list.
 *   - Settings (FR11, D5a): the plain-JDK Map<String,Object> flows straight
 *     through with a defensive copy — no Jackson types cross the boundary.
 *   - Order preservation: stages, inputs, outputs, verify checks keep DTO order.
 * Implements FR7, FR11, D2, D5a of load-pipeline-config.
 */
class PipelineMapperSpec extends Specification {

    private static PipelineMapper.StageEntry entry(String name, StageDto dto) {
        new PipelineMapper.StageEntry(name, dto)
    }

    private static ConfigDto config(String schemaVersion, Integer attemptDefault) {
        new ConfigDto(schemaVersion, attemptDefault == null ? null : new AutonomyDto(attemptDefault))
    }

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
                    new VerifyCheckDto.External('ci/build', '30s', '15m'),
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
            new VerifyCheck.External('ci/build', Duration.ofSeconds(30), Duration.ofMinutes(15)),
            new VerifyCheck.Judge('criteria.md', 'claude-opus-4-1', [maxTokens: 1000], 3),
        ]
        s1.limits() == new AutonomyLimits(5) // override wins
        s1.advancement() == AdvancementMode.AUTO
    }

    // FR11/D5a: the plain-JDK settings map flows through unchanged as a copy
    def "carries executor and judge settings as plain-JDK maps, defensively copied"() {
        given: 'a mutable settings map handed to the DTO'
        def settings = [temperature: 0.2d, nested: [flags: [true, false]]]
        def stage = new StageDto('p', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto('api', 'm', settings), 'i.md',
        [
            new VerifyCheckDto.Judge('c.md', 'jm', settings, 1)
        ], null, 'auto')

        when:
        def definition = PipelineMapper.map(config('1', 1), [entry('p', stage)]).definition()

        then: 'the domain carries an equal map, not a Jackson node'
        def executor = definition.stages()[0].executor()
        executor.settings() == [temperature: 0.2d, nested: [flags: [true, false]]]
        (definition.stages()[0].verify()[0] as VerifyCheck.Judge).settings() == settings

        when: 'the source map is mutated after mapping'
        settings.put('mutated', 'after')

        then: 'the mapped copy is unaffected'
        executor.settings() == [temperature: 0.2d, nested: [flags: [true, false]]]
    }

    // FR11/D5a: absent settings become an empty map, not null
    def "maps absent settings to an empty map"() {
        given: 'an executor and a builtin with no settings/params'
        def stage = new StageDto('p', null, null,
                new ExecutorDto('api', 'm', null), 'i.md',
                [
                    new VerifyCheckDto.Builtin('files_exist', null)
                ], null, 'auto')

        when:
        def s = PipelineMapper.map(config('1', 1), [entry('p', stage)]).definition().stages()[0]

        then: 'executor and builtin params are empty, never null'
        s.executor().settings() == [:]
        (s.verify()[0] as VerifyCheck.Builtin).params() == [:]
    }

    // FR7: default+override resolution both ways, plus the missing-default contract
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
        3            | 5        || 5 // override wins
        3            | null     || 3 // default applies
        null         | 5        || 5 // no default, override still wins
        null         | null     || 0 // both absent → 0 (StageSanityRule flags it)
    }

    // FR5 boundary: enum wire values map to the domain enums, both values each
    def "maps executor type #wireType to #domainType and advancement #wireAdv to #domainAdv"() {
        given:
        def stage = new StageDto('p', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto(wireType, 'm', null), 'i.md', null, null, wireAdv)

        when:
        def s = PipelineMapper.map(config('1', 1), [entry('p', stage)]).definition().stages()[0]

        then:
        s.executor().type() == domainType
        s.advancement() == domainAdv

        where:
        wireType    | wireAdv  || domainType             | domainAdv
        'api'       | 'auto'   || ExecutorType.API       | AdvancementMode.AUTO
        'agent-cli' | 'manual' || ExecutorType.AGENT_CLI | AdvancementMode.MANUAL
    }

    // Missing schemaVersion → blank string (SchemaVersionRule 4.1 reports it)
    def "maps a null schemaVersion to a blank string for the domain rule to flag"() {
        when:
        def definition = PipelineMapper.map(config(null, 1), []).definition()

        then: 'the domain carries a blank version, never null'
        definition.schemaVersion() == ''
    }

    // FR11: well-formed external timing strings parse to Duration
    def "parses external timing string #interval / #timeout to Durations"() {
        given:
        def stage = new StageDto('p', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto('api', 'm', null), 'i.md',
        [
            new VerifyCheckDto.External('ci', interval, timeout)
        ], null, 'auto')

        when:
        def check = PipelineMapper.map(config('1', 1), [entry('p', stage)])
        .definition().stages()[0].verify()[0] as VerifyCheck.External

        then:
        check.interval() == expectedInterval
        check.timeout() == expectedTimeout

        where:
        interval | timeout || expectedInterval          | expectedTimeout
        '30s'    | '15m'   || Duration.ofSeconds(30)    | Duration.ofMinutes(15)
        'PT1H'   | 'PT2H'  || Duration.ofHours(1)       | Duration.ofHours(2)
        '500ms'  | '1s'    || Duration.ofMillis(500)    | Duration.ofSeconds(1)
    }

    // FR11: a null external interval/timeout maps to Duration.ZERO, which
    // StageSanityRule (4.4) flags as non-positive — no crash, no format error
    def "maps a null external #field to zero for the domain rule to flag"() {
        given:
        def stage = new StageDto('p', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto('api', 'm', null), 'i.md',
        [
            new VerifyCheckDto.External('ci', interval, timeout)
        ], null, 'auto')

        when:
        def result = PipelineMapper.map(config('1', 1), [entry('p', stage)])

        then: 'no format error; the absent field becomes zero (never null)'
        result.errors().isEmpty()
        def check = result.definition().stages()[0].verify()[0] as VerifyCheck.External
        check.interval() != null
        check.timeout() != null
        check.interval() == expectedInterval
        check.timeout() == expectedTimeout

        where:
        field      | interval | timeout || expectedInterval       | expectedTimeout
        'interval' | null     | '15m'   || Duration.ZERO          | Duration.ofMinutes(15)
        'timeout'  | '30s'    | null    || Duration.ofSeconds(30) | Duration.ZERO
    }

    // FR11 / duration-parse placement: a malformed timing string is a located
    // ConfigError; the definition is absent so 6.5 aggregates the error
    def "reports a malformed external #field as a located error"() {
        given:
        def stage = new StageDto('p', [new ArtifactInputDto.Source()], [new ArtifactOutputDto('o')],
        new ExecutorDto('api', 'm', null), 'i.md',
        [
            new VerifyCheckDto.External('ci', interval, timeout)
        ], null, 'auto')

        when:
        def result = PipelineMapper.map(config('1', 1), [entry('build', stage)])

        then: 'exactly one located error, no definition'
        result.definition() == null
        result.errors() == [
            new ConfigError('stages/build/stage.yaml', where, message),
        ]

        where:
        field      | interval  | timeout   || where              | message
        'interval' | 'banana'  | '15m'     || 'verify[0].interval' | "malformed duration 'banana'; use e.g. '30s', '15m', '2h'"
        'timeout'  | '30s'     | 'nonsense'|| 'verify[0].timeout'  | "malformed duration 'nonsense'; use e.g. '30s', '15m', '2h'"
    }

    // Aggregation: malformed durations across stages/checks all collected,
    // located by stage manifest and verify index, in stage then check order
    def "aggregates all malformed durations across stages in order"() {
        given:
        def s0 = new StageDto('p', null, [new ArtifactOutputDto('o0')],
        new ExecutorDto('api', 'm', null), 'i.md',
        [
            new VerifyCheckDto.External('a', 'bad1', '1m')
        ], null, 'auto')
        def s1 = new StageDto('p', null, [new ArtifactOutputDto('o1')],
        new ExecutorDto('api', 'm', null), 'i.md',
        [
            new VerifyCheckDto.Command('x'),
            new VerifyCheckDto.External('b', '1s', 'bad2'),
        ] as List<VerifyCheckDto>, null, 'auto')

        when:
        def result = PipelineMapper.map(config('1', 1), [
            entry('plan', s0),
            entry('build', s1)
        ])

        then: 'both errors, located by stage manifest and verify index, in order'
        result.definition() == null
        result.errors() == [
            new ConfigError('stages/plan/stage.yaml', 'verify[0].interval', "malformed duration 'bad1'; use e.g. '30s', '15m', '2h'"),
            new ConfigError('stages/build/stage.yaml', 'verify[1].timeout', "malformed duration 'bad2'; use e.g. '30s', '15m', '2h'"),
        ]
    }

    // Null purpose/instructions → blank strings so the domain (future rules) see
    // them as blank rather than the mapper NPE-ing
    def "maps null purpose and instructions to blank strings"() {
        given:
        def stage = new StageDto(null, null, null,
                new ExecutorDto('api', 'm', null), null, null, null, 'auto')

        when:
        def s = PipelineMapper.map(config('1', 1), [entry('p', stage)]).definition().stages()[0]

        then:
        s.purpose() == ''
        s.instructionsRef() == ''
    }

    // Defensive boundary: a null executor (structurally a 5.2 error, never
    // reaching the mapper in the real flow) maps to a default API executor with
    // a blank model, so StageSanityRule (4.4) reports the blank model — the same
    // null → blank contract, no NPE
    def "maps a null executor to a default API executor with a blank model"() {
        given:
        def stage = new StageDto('p', null, null, null, 'i.md', null, null, 'auto')

        when:
        def executor = PipelineMapper.map(config('1', 1), [entry('p', stage)]).definition().stages()[0].executor()

        then:
        executor.type() == ExecutorType.API
        executor.model() == ''
        executor.settings() == [:]
    }

    // Null model → blank string so StageSanityRule (4.4) reports it
    def "maps a null executor model to a blank string"() {
        given:
        def stage = new StageDto('p', null, null,
                new ExecutorDto('api', null, null), 'i.md',
                [
                    new VerifyCheckDto.Judge('c.md', null, null, 1)
                ], null, 'auto')

        when:
        def s = PipelineMapper.map(config('1', 1), [entry('p', stage)]).definition().stages()[0]

        then:
        s.executor().model() == ''
        (s.verify()[0] as VerifyCheck.Judge).model() == ''
    }

    // Null producerOutputId / output id / builtin name → blank strings so the
    // graph rule (4.3) can report them
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
        def s = PipelineMapper.map(config('1', 1), [entry('p', stage)]).definition().stages()[0]

        then:
        s.inputs() == [
            new ArtifactInput.Internal('')
        ]
        s.outputs() == [new ArtifactOutput('')]
        (s.verify()[0] as VerifyCheck.Builtin).name() == ''
    }

    // Null command / criteriaFile / checkId → blank strings
    def "maps null command, criteria file and check id to blank strings"() {
        given:
        def stage = new StageDto('p', null, null,
                new ExecutorDto('api', 'm', null), 'i.md',
                [
                    new VerifyCheckDto.Command(null),
                    new VerifyCheckDto.External(null, '1s', '1m'),
                    new VerifyCheckDto.Judge(null, 'jm', null, 1),
                ] as List<VerifyCheckDto>, null, 'auto')

        when:
        def checks = PipelineMapper.map(config('1', 1), [entry('p', stage)]).definition().stages()[0].verify()

        then:
        (checks[0] as VerifyCheck.Command).command() == ''
        (checks[1] as VerifyCheck.External).checkId() == ''
        (checks[2] as VerifyCheck.Judge).criteriaFile() == ''
    }

    // Null votes → 0 so StageSanityRule (4.4) flags it (votes >= 1 and odd)
    def "maps null judge votes to zero for the domain rule to flag"() {
        given:
        def stage = new StageDto('p', null, null,
                new ExecutorDto('api', 'm', null), 'i.md',
                [
                    new VerifyCheckDto.Judge('c.md', 'jm', null, null)
                ], null, 'auto')

        when:
        def judge = PipelineMapper.map(config('1', 1), [entry('p', stage)])
        .definition().stages()[0].verify()[0] as VerifyCheck.Judge

        then:
        judge.votes() == 0
    }

    // Empty pipeline maps to an empty stage list (StageOrderRule 4.2 reports it)
    def "maps an empty stage list to an empty-stage definition"() {
        when:
        def definition = PipelineMapper.map(config('1', 1), []).definition()

        then:
        definition.stages() == []
    }

    // Absent inputs/outputs/verify sections map to empty lists, order preserved
    def "maps absent input, output and verify sections to empty lists"() {
        given:
        def stage = new StageDto('p', null, null,
                new ExecutorDto('api', 'm', null), 'i.md', null, null, 'auto')

        when:
        def s = PipelineMapper.map(config('1', 1), [entry('p', stage)]).definition().stages()[0]

        then:
        s.inputs() == []
        s.outputs() == []
        s.verify() == []
    }
}
