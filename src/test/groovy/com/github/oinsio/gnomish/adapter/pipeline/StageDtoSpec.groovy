package com.github.oinsio.gnomish.adapter.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * StageDto round-trip: stage.yaml deserializes into the adapter's DTO, mirroring
 * the eight stage-contract sections and all four verify-check variants plus both
 * artifact-input kinds, selected by an explicit type/kind discriminator (D5).
 * Executor settings and builtin params deserialize into plain JDK maps, never a
 * Jackson JsonNode (D5a), so the domain mapper (5.3) stays Jackson-free.
 * Implements FR1, FR2, FR4 (DTO shapes), D2, D5, D5a of load-pipeline-config.
 */
class StageDtoSpec extends Specification {

    private final ObjectMapper yaml = PipelineYaml.mapper()

    private static final String FULL_STAGE = '''\
        purpose: "Turn the plan into a verified implementation"
        inputs:
          - kind: source
          - kind: internal
            producerOutputId: plan-doc
        outputs:
          - id: impl-diff
          - id: test-report
        executor:
          type: agent-cli
          model: claude-sonnet-4-5
          settings:
            temperature: 0
            permissionMode: acceptEdits
            allowedTools:
              - Read
              - Edit
        instructions: stages/implement/instructions.md
        verify:
          - type: builtin
            name: files_exist
            params:
              paths:
                - README.md
          - type: command
            command: "./gradlew check"
          - type: external
            checkId: ci/build
            interval: 30s
            timeout: 15m
          - type: judge
            criteriaFile: stages/implement/acceptance.md
            model: claude-sonnet-4-5
            settings:
              temperature: 0
            votes: 3
        autonomy:
          attemptLimit: 5
        advancement: manual
        '''.stripIndent()

    def "stage.yaml deserializes the eight sections into the StageDto"() {
        when: 'the full stage manifest is read'
        def dto = yaml.readValue(FULL_STAGE, StageDto)

        then: 'the scalar sections are exposed'
        dto.purpose() == 'Turn the plan into a verified implementation'
        dto.instructions() == 'stages/implement/instructions.md'
        dto.advancement() == 'manual'

        and: 'the per-stage autonomy override is carried'
        dto.autonomy().attemptLimit() == 5

        and: 'outputs keep their ids in declaration order'
        dto.outputs()*.id() == ['impl-diff', 'test-report']
    }

    def "both artifact-input kinds deserialize by their kind discriminator"() {
        when: 'the stage is read'
        def dto = yaml.readValue(FULL_STAGE, StageDto)

        then: 'the source input carries no producer, the internal input carries one'
        def inputs = dto.inputs()
        inputs.size() == 2
        inputs[0] instanceof ArtifactInputDto.Source
        inputs[1] instanceof ArtifactInputDto.Internal
        (inputs[1] as ArtifactInputDto.Internal).producerOutputId() == 'plan-doc'
    }

    def "the executor deserializes with settings as a plain JDK map"() {
        when: 'the stage is read'
        def dto = yaml.readValue(FULL_STAGE, StageDto)

        then: 'executor type and pinned model are exposed'
        def exec = dto.executor()
        exec.type() == 'agent-cli'
        exec.model() == 'claude-sonnet-4-5'

        and: 'settings are plain JDK types (Map/List/String/Number), not JsonNode'
        exec.settings() instanceof Map
        exec.settings().get('temperature') instanceof Integer
        exec.settings().get('permissionMode') == 'acceptEdits'
        exec.settings().get('allowedTools') instanceof List
        exec.settings().get('allowedTools') == ['Read', 'Edit']
    }

    def "all four verify-check variants deserialize by their type discriminator, order preserved"() {
        when: 'the stage is read'
        def checks = yaml.readValue(FULL_STAGE, StageDto).verify()

        then: 'the four variants appear in declared order'
        checks.size() == 4
        checks[0] instanceof VerifyCheckDto.Builtin
        checks[1] instanceof VerifyCheckDto.Command
        checks[2] instanceof VerifyCheckDto.External
        checks[3] instanceof VerifyCheckDto.Judge
    }

    def "the builtin check carries its name and params as a plain JDK map"() {
        when: 'the stage is read'
        def builtin = yaml.readValue(FULL_STAGE, StageDto).verify()[0] as VerifyCheckDto.Builtin

        then: 'the engine check name and its declarative params are exposed'
        builtin.name() == 'files_exist'
        builtin.params() instanceof Map
        builtin.params().get('paths') == ['README.md']
    }

    def "the command check carries its command line"() {
        when: 'the stage is read'
        def command = yaml.readValue(FULL_STAGE, StageDto).verify()[1] as VerifyCheckDto.Command

        then: 'the command line is exposed'
        command.command() == './gradlew check'
    }

    def "the external check carries its identifier and raw timing strings"() {
        when: 'the stage is read'
        def external = yaml.readValue(FULL_STAGE, StageDto).verify()[2] as VerifyCheckDto.External

        then: 'the check id and both timing strings are exposed verbatim (parsing is 5.3)'
        external.checkId() == 'ci/build'
        external.interval() == '30s'
        external.timeout() == '15m'
    }

    def "the judge check carries criteria, model, settings and votes"() {
        when: 'the stage is read'
        def judge = yaml.readValue(FULL_STAGE, StageDto).verify()[3] as VerifyCheckDto.Judge

        then: 'the four judge fields are exposed, settings as a plain JDK map'
        judge.criteriaFile() == 'stages/implement/acceptance.md'
        judge.model() == 'claude-sonnet-4-5'
        judge.settings() == [temperature: 0]
        judge.votes() == 3
    }

    def "a stage without an autonomy override leaves the override null"() {
        given: 'a minimal stage with no autonomy block'
        def body = '''\
            purpose: "Plan the work"
            inputs:
              - kind: source
            outputs:
              - id: plan-doc
            executor:
              type: api
              model: claude-opus-4
            instructions: stages/plan/instructions.md
            verify:
              - type: command
                command: "true"
            advancement: auto
            '''.stripIndent()

        when: 'the stage is read'
        def dto = yaml.readValue(body, StageDto)

        then: 'the missing override is null and an absent settings block is null'
        dto.autonomy() == null
        dto.executor().settings() == null
        dto.advancement() == 'auto'
    }
}
