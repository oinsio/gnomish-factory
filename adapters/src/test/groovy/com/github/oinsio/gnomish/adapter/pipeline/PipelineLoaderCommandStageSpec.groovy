package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.CommandLine
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * A {@code command} stage seen through the loader end to end (FR1, FR2, UX2 of
 * add-command-executor): the wire token maps to {@code ExecutorType.COMMAND}, the
 * {@code executor.command} reaches the typed model as a {@code CommandLine}, and every misuse of
 * the Mechanism's {@code command}/{@code model} pair is one located error on the stage manifest —
 * a blank value included, which the mapping would otherwise erase into an absent one.
 */
class PipelineLoaderCommandStageSpec extends Specification implements PipelineLoaderFixtureSupport {

    static final String MANIFEST = 'stages/gen/stage.yaml'
    static final String ACME = 'executors:\n  acme:\n    type: program\n    program: [acme-executor]\n'

    @TempDir
    Path root

    void tree(String executorBlock) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n' + ACME)
        write('pipeline.yaml', 'stages:\n  - gen\n')
        write('stages/gen/instructions.md', 'generate\n')
        write(MANIFEST, "purpose: generate sources\n${executorBlock}instructions: stages/gen/instructions.md\nadvancement: auto\n")
    }

    List<ConfigError> errors() {
        def outcome = loadTree()
        assert outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors()
    }

    // FR1, FR2: the command token and the command reach the typed model
    // Scenario: pipeline/pipeline-config / Well-formed command stage is accepted
    def "a well-formed command stage loads, the typed model carrying its command"() {
        given:
        tree('executor:\n  type: command\n  command: "./gradlew generateSources"\n')

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Loaded
        def executor = (outcome as LoadOutcome.Loaded).definition().stages()[0].executor()
        executor.type() == ExecutorType.COMMAND
        executor.command() == new CommandLine('./gradlew generateSources')
        executor.model() == ''
    }

    // FR2: the command is carried exactly as declared — no trimming
    def "the command is carried verbatim, surrounding whitespace included"() {
        given:
        tree('executor:\n  type: command\n  command: "  make gen  "\n')

        expect:
        (loadTree() as LoadOutcome.Loaded).definition().stages()[0].executor().command() == new CommandLine('  make gen  ')
    }

    // FR2, UX2: a command stage declares no model — a blank one is still declared
    // Scenario: pipeline/pipeline-config / Model on a command stage is rejected
    def "a command stage declaring a model (#model) is a located error naming the forbidden field"() {
        given:
        tree("executor:\n  type: command\n  command: make gen\n  model: ${model}\n")

        expect:
        errors() == [
            new ConfigError(MANIFEST, 'executor.model',
            'executor model is not allowed on a command stage; a command stage runs its declared command, not a model')
        ]

        where:
        model << ['some-model', '""', '"   "']
    }

    // FR2, UX2: an absent or blank command names no mechanism
    // Scenario: pipeline/pipeline-config / Command stage requires its command
    def "a command stage with #shape is a located error naming the missing field"() {
        given:
        tree("executor:\n  type: command\n${line}")

        expect:
        errors() == [
            new ConfigError(MANIFEST, 'executor.command',
            'missing required executor command; a command stage must declare the command it runs')
        ]

        where:
        shape | line
        'no command' | ''
        'a null command' | '  command:\n'
        'an empty command' | '  command: ""\n'
        'a blank command' | '  command: "   "\n'
    }

    // FR2, UX2: only a command stage declares a command — a blank one is still declared
    // Scenario: pipeline/pipeline-config / Command field on another executor type is rejected
    def "a #type stage declaring a command (#command) is a located error naming the forbidden field"() {
        given:
        tree("executor:\n  type: ${type}\n${extra}  command: ${command}\n")

        when:
        def found = errors()

        then:
        found.contains(new ConfigError(MANIFEST, 'executor.command',
                "executor command is not allowed on a ${type} stage; only a command stage declares a command" as String))
        found.findAll { it.where() == 'executor.command' }.size() == 1

        where:
        [type, extra, command] << [
            [
                ['api', '  model: m\n'],
                ['agent-cli', '  model: m\n'],
                ['program', '  name: acme\n']
            ],
            ['make gen', '""', '"   "']
        ].combinations().collect { pair, cmd -> [pair[0], pair[1], cmd] }
    }

    // FR1: the new token joins the known set; an unknown one is still a located error
    def "an unknown executor token is a located error naming the known set, command included"() {
        given:
        tree('executor:\n  type: shell\n  model: m\n')

        expect:
        errors().contains(new ConfigError(MANIFEST, 'executor.type',
                "unknown executor 'shell'; known executors are api, agent-cli, program, command"))
    }
}
