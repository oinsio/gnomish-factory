package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Invalid-fixture battery, tier 1 — schema version (FR9) and structural shape
 * (FR5): one minimal {@code .gnomish/} tree per validation rule, each asserting the
 * loader returns {@code Invalid} with EXACTLY the expected located
 * {@link ConfigError} set (success metric M2, UX1/UX2). Every row builds the valid
 * baseline then mutates the one file that isolates the rule, so no other tier trips
 * first (the fixture stays otherwise valid).
 *
 * <p>These are the checks the adapter's {@code StructuralParse}/{@code
 * StructuralValidation} and the domain {@code SchemaVersionRule} own: a missing or
 * unsupported schema version; malformed YAML; a missing required field; an unknown
 * executor / advancement / verify-type / input-kind enum; and a scalar/list type
 * mismatch. Each asserts the exact {@code (file, where, message)} the real producer
 * emits.
 *
 * <p>M2 / UX1 / UX2 — FR9, FR5 of load-pipeline-config.
 */
class InvalidStructuralBatterySpec extends Specification implements InvalidFixtureSupport {

    @TempDir
    Path root

    Path getRoot() {
        root
    }

    def "M2/FR9: schema-version faults each yield exactly the located config.yaml error"() {
        given: 'a valid baseline whose config.yaml the row rewrites'
        writeValidBaseline()
        write('config.yaml', config)

        when:
        def errors = loadInvalid()

        then: 'exactly the one located schemaVersion error'
        errors == [expected]

        where:
        rule                 | config                                                  || expected
        'missing schema'     | 'autonomy:\n  attemptLimit: 2\n'                        || new ConfigError('config.yaml', 'schemaVersion', "missing required schemaVersion; supported version is '1'")
        'unsupported schema' | 'schemaVersion: "2"\nautonomy:\n  attemptLimit: 2\n'    || new ConfigError('config.yaml', 'schemaVersion', "unsupported schemaVersion '2'; supported version is '1'")
    }

    def "M2/FR5: a structurally broken stage.yaml yields exactly the located structural error"() {
        given: 'a valid baseline whose plan manifest the row rewrites'
        writeValidBaseline()
        write('stages/plan/stage.yaml', manifest)

        when:
        def errors = loadInvalid()

        then: 'exactly the one located structural error the producer emits'
        errors == [expected]

        where:
        rule                    | manifest         || expected
        'missing purpose'       | noPurpose()      || new ConfigError('stages/plan/stage.yaml', 'purpose', "missing required field 'purpose'")
        'missing instructions'  | noInstructions() || new ConfigError('stages/plan/stage.yaml', 'instructions', "missing required field 'instructions'")
        'missing advancement'   | noAdvancement()  || new ConfigError('stages/plan/stage.yaml', 'advancement', "missing required field 'advancement'")
        'unknown advancement'   | badAdvancement() || new ConfigError('stages/plan/stage.yaml', 'advancement', "unknown advancement 'sideways'; known modes are auto, manual")
    }

    def "M2/FR5: a missing executor.type falls back to the mapper's api default, so the structural error and the api-rejection error (D6 of add-agent-executor) both fire"() {
        given: 'the tier interaction: an absent executor.type cannot be typed, so the mapper defaults to api,'
        and: 'which ApiExecutorRule then independently rejects (task 9.2, FR10/UX2/D6)'
        writeValidBaseline()
        write('stages/plan/stage.yaml', noExecutorType())

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'executor.type', "missing required field 'executor.type'"),
            new ConfigError('stages/plan/stage.yaml', 'executor.type', "api executor is not yet supported; 'agent-cli' is the only supported executor type currently"),
        ]
    }

    def "M2/FR5: an unknown executor.type falls back to the mapper's api default, so the structural error and the api-rejection error both fire"() {
        given: 'the same tier interaction as the missing-type case: an unrecognized value cannot be typed either'
        writeValidBaseline()
        write('stages/plan/stage.yaml', badExecutor())

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'executor.type', "unknown executor 'wat'; known executors are api, agent-cli"),
            new ConfigError('stages/plan/stage.yaml', 'executor.type', "api executor is not yet supported; 'agent-cli' is the only supported executor type currently"),
        ]
    }

    def "M2/FR5: a wholly absent executor yields the structural, mapped blank-model, and api-rejection errors"() {
        given: 'no executor section at all; the mapper substitutes a default executor with a blank model'
        and: 'and executor type api (both the domain "missing model" and the api-rejection errors fire —'
        and: 'inherent tier interaction: a missing executor cannot carry a pinned model or a real type)'
        writeValidBaseline()
        write('stages/plan/stage.yaml', noExecutor())

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'executor', "missing required field 'executor'"),
            new ConfigError('stages/plan/stage.yaml', 'executor.model', 'missing required executor model; the model must be pinned in the manifest for every executor type'),
            new ConfigError('stages/plan/stage.yaml', 'executor.type', "api executor is not yet supported; 'agent-cli' is the only supported executor type currently"),
        ]
    }

    def "M2/FR5: an unknown verify type or input kind discriminator yields exactly its located error"() {
        given:
        writeValidBaseline()
        write('stages/plan/stage.yaml', manifest)

        when:
        def errors = loadInvalid()

        then: 'the discriminator parse error short-circuits only this file, so it stands alone'
        errors == [expected]

        where:
        rule                  | manifest             || expected
        'unknown verify type' | badVerifyType()      || new ConfigError('stages/plan/stage.yaml', 'verify[0]', "unknown verify check type 'lint'; known types are builtin, command, external, judge")
        'unknown input kind'  | badInputKind()       || new ConfigError('stages/plan/stage.yaml', 'inputs[0]', "unknown input kind 'external'; known kinds are internal, source")
    }

    def "M2/FR5: malformed YAML in a stage manifest yields exactly its located parse error"() {
        given:
        writeValidBaseline()
        write('stages/plan/stage.yaml', 'purpose: "unterminated\n')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'stages/plan/stage.yaml', 'malformed YAML: the file is not well-formed and cannot be parsed')
        ]
    }

    def "M2/FR5: a type mismatch (scalar where a list is required) yields exactly its located error"() {
        given: 'pipeline.yaml declares stages as a scalar, not a list; no stage dir exists, so nothing dangles'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages: plan\n')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('pipeline.yaml', 'stages', "type mismatch: 'stages' has the wrong YAML type")
        ]
    }

    private static String noPurpose() {
        '''\
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
advancement: auto
'''
    }

    private static String noExecutor() {
        '''\
purpose: plan
instructions: stages/plan/instructions.md
advancement: auto
'''
    }

    private static String noExecutorType() {
        '''\
purpose: plan
executor:
  model: plan-model
instructions: stages/plan/instructions.md
advancement: auto
'''
    }

    private static String noInstructions() {
        '''\
purpose: plan
executor:
  type: agent-cli
  model: plan-model
advancement: auto
'''
    }

    private static String noAdvancement() {
        '''\
purpose: plan
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
'''
    }

    private static String badExecutor() {
        '''\
purpose: plan
executor:
  type: wat
  model: plan-model
instructions: stages/plan/instructions.md
advancement: auto
'''
    }

    private static String badAdvancement() {
        '''\
purpose: plan
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
advancement: sideways
'''
    }

    private static String badVerifyType() {
        '''\
purpose: plan
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: lint
advancement: auto
'''
    }

    private static String badInputKind() {
        '''\
purpose: plan
inputs:
  - kind: external
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
advancement: auto
'''
    }
}
