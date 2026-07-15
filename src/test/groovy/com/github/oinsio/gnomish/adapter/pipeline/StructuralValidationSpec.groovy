package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import spock.lang.Specification

/**
 * StructuralValidation checks the shape of an already-parsed DTO: the required
 * fields the mapper (task 5.3) needs, and the raw-string enums Jackson accepts
 * verbatim (executor type, advancement). Missing required fields deserialize to
 * null because every wire field is nullable (D2); an unknown enum value is a
 * raw string. Both become located ConfigErrors here (FR5), so the mapper only
 * ever sees a structurally acceptable DTO.
 *
 * Boundary (5.2 vs later tasks): fields a pure domain rule already owns are NOT
 * re-checked to avoid double-reporting — config.yaml schemaVersion presence is
 * SchemaVersionRule (4.1); an empty pipeline stage list is StageOrderRule (4.2);
 * a blank executor/judge model is StageSanityRule (4.4); external/judge value
 * ranges are StageSanityRule (4.4). Structural validation catches only shape
 * problems no domain rule can (a null where the mapper needs a value, an unknown
 * discriminator/enum).
 * Implements FR5 of load-pipeline-config.
 */
class StructuralValidationSpec extends Specification {

    // --- config.yaml -------------------------------------------------------

    def "a config.yaml with a schema version and no autonomy is structurally clean"() {
        expect: 'schemaVersion presence is a domain rule, not a structural one'
        StructuralValidation.checkConfig(new ConfigDto('1', null)).isEmpty()
        StructuralValidation.checkConfig(new ConfigDto(null, null)).isEmpty()
    }

    // --- pipeline.yaml -----------------------------------------------------

    def "pipeline.yaml with a stages list is structurally clean; an absent stages key is a structural error"() {
        expect: 'a present list (even empty — emptiness is a domain rule) is clean'
        StructuralValidation.checkPipeline(new PipelineDto(['plan'])).isEmpty()
        StructuralValidation.checkPipeline(new PipelineDto([])).isEmpty()

        and: 'an absent stages key (null) is a structural shape error'
        def errors = StructuralValidation.checkPipeline(new PipelineDto(null))
        errors.size() == 1
        with(errors[0] as ConfigError) {
            file() == 'pipeline.yaml'
            where() == 'stages'
            message() == "missing required field 'stages'"
        }
    }

    // --- stage.yaml: a fully-populated stage is clean ----------------------

    def "a fully-populated stage.yaml is structurally clean"() {
        given: 'a stage with every required field present and known enums'
        def stage = new StageDto(
                'purpose',
                [
                    new ArtifactInputDto.Source(),
                    new ArtifactInputDto.Internal('plan-doc')
                ] as List<ArtifactInputDto>,
                [
                    new ArtifactOutputDto('impl-diff')
                ],
                new ExecutorDto('agent-cli', 'model', null),
                'stages/build/instructions.md',
                [
                    new VerifyCheckDto.Command('true')
                ],
                null,
                'auto')

        expect:
        StructuralValidation.checkStage('stages/build/stage.yaml', stage).isEmpty()
    }

    // --- stage.yaml: each missing required field / unknown enum ------------

    def "each structural stage problem yields its exact located error"() {
        when:
        def errors = StructuralValidation.checkStage('stages/build/stage.yaml', stage)

        then:
        errors.size() == 1
        with(errors[0] as ConfigError) {
            file() == 'stages/build/stage.yaml'
            where() == expectedWhere
            message() == expectedMessage
        }

        where:
        scenario                     | stage                                     || expectedWhere        | expectedMessage
        'missing purpose'            | stageWith(purpose: null)                  || 'purpose'            | "missing required field 'purpose'"
        'missing executor block'     | stageWith(executor: null)                 || 'executor'           | "missing required field 'executor'"
        'missing executor type'      | stageWith(executor: exec(type: null))     || 'executor.type'      | "missing required field 'executor.type'"
        'unknown executor type'      | stageWith(executor: exec(type: 'foo'))    || 'executor.type'      | "unknown executor 'foo'; known executors are api, agent-cli"
        'missing instructions'       | stageWith(instructions: null)             || 'instructions'       | "missing required field 'instructions'"
        'missing advancement'        | stageWith(advancement: null)              || 'advancement'        | "missing required field 'advancement'"
        'unknown advancement'        | stageWith(advancement: 'later')           || 'advancement'        | "unknown advancement 'later'; known modes are auto, manual"
        'output without id'          | stageWith(outputs: [new ArtifactOutputDto(null)]) || 'outputs[0].id' | "missing required field 'id'"
        'internal input without ref' | stageWith(inputs: [
            new ArtifactInputDto.Internal(null)
        ]) || 'inputs[0].producerOutputId' | "missing required field 'producerOutputId'"
    }

    def "structural problems are all reported in one pass"() {
        given: 'a stage missing purpose and carrying an unknown executor type'
        def stage = stageWith(purpose: null, executor: exec(type: 'foo'))

        when:
        def errors = StructuralValidation.checkStage('stages/build/stage.yaml', stage)

        then: 'both problems appear, purpose first (declaration order)'
        errors.size() == 2
        (errors[0] as ConfigError).where() == 'purpose'
        (errors[1] as ConfigError).where() == 'executor.type'
    }

    def "an absent verify list and absent inputs/outputs are structurally clean"() {
        given: 'a minimal stage: no verify, no inputs, no outputs'
        def stage = new StageDto(
                'purpose', null, null,
                new ExecutorDto('api', 'model', null),
                'instructions.md', null, null, 'auto')

        expect: 'those sections are optional at the structural layer'
        StructuralValidation.checkStage('stages/build/stage.yaml', stage).isEmpty()
    }

    // --- helpers -----------------------------------------------------------

    private static ExecutorDto exec(Map overrides) {
        new ExecutorDto(
                overrides.containsKey('type') ? overrides.type : 'agent-cli',
                overrides.containsKey('model') ? overrides.model : 'model',
                overrides.containsKey('settings') ? overrides.settings : null)
    }

    private static StageDto stageWith(Map overrides) {
        new StageDto(
                overrides.containsKey('purpose') ? overrides.purpose : 'purpose',
                overrides.containsKey('inputs') ? overrides.inputs : [new ArtifactInputDto.Source()],
                overrides.containsKey('outputs') ? overrides.outputs : [
                    new ArtifactOutputDto('impl-diff')
                ],
                overrides.containsKey('executor') ? overrides.executor : new ExecutorDto('agent-cli', 'model', null),
                overrides.containsKey('instructions') ? overrides.instructions : 'instructions.md',
                overrides.containsKey('verify') ? overrides.verify : [
                    new VerifyCheckDto.Command('true')
                ],
                overrides.containsKey('autonomy') ? overrides.autonomy : null,
                overrides.containsKey('advancement') ? overrides.advancement : 'auto')
    }
}
