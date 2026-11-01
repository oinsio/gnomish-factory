package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The {@code executors:} section and a stage's {@code executor.name} seen through the loader (FR9,
 * FR10, UX1 of define-executor-contract; design D5 phase a, the only check law load runs): a sound
 * declaration reaches the typed model, every fault is a located error, and no program is spawned.
 */
class PipelineLoaderExecutorDeclarationSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    static final String ACME = '''\
executors:
  acme:
    type: program
    program: [acme-executor, --json]
    settings:
      maxTurns: 20
    credentials: [ACME_API_KEY]
    egress: [api.acme.example]
'''

    void tree(String executors, String executorBlock) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n' + executors)
        write('pipeline.yaml', 'stages:\n  - lint\n')
        write('stages/lint/instructions.md', 'lint it\n')
        write('stages/lint/stage.yaml', "purpose: lint\n${executorBlock}instructions: stages/lint/instructions.md\nadvancement: auto\n")
    }

    List<ConfigError> errors() {
        def outcome = loadTree()
        assert outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors()
    }

    // FR9, FR10: the typed model carries the declaration and the stage's reference to it
    // Scenario: pipeline/pipeline-config / Program stage names a declared executor
    def "a program stage naming a declared executor loads, the model carrying the reference"() {
        given:
        tree(ACME, 'executor:\n  type: program\n  name: acme\n  model: acme-large\n')

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Loaded
        def model = (outcome as LoadOutcome.Loaded).definition()
        model.stages()[0].executor().type() == ExecutorType.PROGRAM
        model.stages()[0].executor().name() == 'acme'
        model.stages()[0].executor().model() == 'acme-large'
        model.executors() == [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, ['acme-executor', '--json'],
            [maxTurns: 20], ['ACME_API_KEY'], ['api.acme.example'])
        ]
    }

    // FR10: the model is optional on a program stage
    // Scenario: pipeline/pipeline-config / Program stage may omit the model
    def "a program stage with no model loads"() {
        given:
        tree(ACME, 'executor:\n  type: program\n  name: acme\n')

        expect:
        loadTree() instanceof LoadOutcome.Loaded
    }

    // FR9: an unknown name is located at the stage and quotes the name
    // Scenario: pipeline/pipeline-config / Unknown executor name is a located error
    def "a stage naming an undeclared executor fails with a located error naming the stage and the name"() {
        given:
        tree(executors, 'executor:\n  type: program\n  name: acme\n')

        expect:
        errors() == [
            new ConfigError('stages/lint/stage.yaml', 'executor.name', message)
        ]

        where:
        executors || message
        '' || "unknown executor 'acme'; config.yaml declares no executors"
        'executors:\n  lint-bot:\n    type: program\n    program: [lint]\n' || "unknown executor 'acme'; declared executors are lint-bot"
    }

    // FR10: a program stage must name its declaration
    // Scenario: pipeline/pipeline-config / Program stage without a name is rejected
    def "a program stage without executor.name fails with a located error naming the stage and the field"() {
        given:
        tree(ACME, 'executor:\n  type: program\n')

        expect:
        errors() == [
            new ConfigError('stages/lint/stage.yaml', 'executor.name',
            "missing required field 'executor.name'; a program stage names the executor declaration it runs")
        ]
    }

    // FR9, UX1: structural faults name the declaration and the field, and no program runs
    // Scenario: pipeline/pipeline-config / Structural errors are caught at law load
    def "a declaration with #fault fails with a located error, and no program is spawned"() {
        given: 'a program whose run would leave a sentinel file'
        def sentinel = root.resolve('sentinel.txt')
        tree("executors:\n  acme:\n${body.replace('SENTINEL', sentinel.toString())}", 'executor:\n  type: program\n  name: acme\n')

        expect:
        errors() == [
            new ConfigError('config.yaml', where, message)
        ]
        !Files.exists(sentinel)

        where:
        fault | body || where | message
        'unknown type' | '    type: wasm\n    program: [touch, SENTINEL]\n' || 'executors.acme.type' | "unknown executor type 'wasm'; known executor types are program"
        'no argv' | '    type: program\n    credentials: [ACME_API_KEY]\n' || 'executors.acme.program' | "missing required field 'program'; a program declaration names its argv, the program first"
    }

    // FR9: a sound program declaration is inert data at load — its program never runs
    def "a sound program declaration is never run at load"() {
        given:
        def sentinel = root.resolve('sentinel.txt')
        tree("executors:\n  acme:\n    type: program\n    program: [touch, ${sentinel}]\n", 'executor:\n  type: program\n  name: acme\n')

        expect:
        loadTree() instanceof LoadOutcome.Loaded
        !Files.exists(sentinel)
    }

    // FR9: an unknown declaration field is the parse tier's unknown-field error
    def "an unknown field in a declaration is a located parse error"() {
        given:
        tree('executors:\n  acme:\n    type: program\n    program: [a]\n    argv: [b]\n', 'executor:\n  type: program\n  name: acme\n')

        expect:
        errors()*.message().any { it.contains("unknown field 'argv'") }
    }
}
