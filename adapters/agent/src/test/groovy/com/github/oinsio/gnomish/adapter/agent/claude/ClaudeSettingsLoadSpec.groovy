package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.adapter.pipeline.CheckSettingsValidator
import com.github.oinsio.gnomish.adapter.pipeline.ConnectionAdmission
import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, UX2, D6 of extract-agent-round-runner: loading a law holds every agent-cli stage's and every
 * judge check's settings to the schema the Claude dialect's describe declares, through the loader
 * exactly as the composition root wires it — the dialect's judge bound as the loader's agent
 * settings judge — so a typo is a located load error before any round.
 *
 * <p>FR11, UX2, D7 of add-agent-executor.
 */
class ClaudeSettingsLoadSpec extends Specification {

    @TempDir
    Path root

    // Scenario: executor/claude-dialect / Settings typo is a load error
    def "FR7: a judge check's settings typo fails the load with an error naming the stage, the check and the key"() {
        given: 'a valid law except the build stage\'s judge check settings carry allowedTols'
        law('agent-cli', '', '''\
    settings:
      allowedTols:
        - Read
''')

        when:
        def outcome = load()

        then:
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.size() == 1
        errors[0].file() == 'stages/build/stage.yaml'
        errors[0].where() == 'verify[0].settings.allowedTols'
    }

    // Scenario: pipeline/pipeline-config / Agent settings are checked against the executor's schema
    def "FR7: an agent-cli stage's unrecognized settings key fails the load, located at the stage and key"() {
        given:
        law('agent-cli', '''\
  settings:
    allowedTols:
      - Read
''', '')

        expect:
        (load() as LoadOutcome.Invalid).errors()*.where() == [
            'executor.settings.allowedTols'
        ]
    }

    // Scenario: pipeline/pipeline-config / Agent settings are checked against the executor's schema
    def "FR7: a judge check's malformed maxTurns fails the load even under a command stage"() {
        given:
        law('command', '', '''\
    settings:
      maxTurns: "five"
''')

        expect:
        (load() as LoadOutcome.Invalid).errors().collect {
            [it.file(), it.where()]
        } == [
            [
                'stages/build/stage.yaml',
                'verify[0].settings.maxTurns'
            ]
        ]
    }

    def "FR7: a law whose agent settings are well-formed loads"() {
        given:
        law('agent-cli', '''\
  settings:
    allowedTools: [Read]
    roundTimeout: PT10M
''', '''\
    settings:
      maxTurns: 3
''')

        expect:
        load() instanceof LoadOutcome.Loaded
    }

    private LoadOutcome load() {
        PipelineLoader.loadConfiguration(root, [:], [:], ClaudeSettings.&validate as CheckSettingsValidator,
        ConnectionAdmission.of(ConnectionProfiles.none()), [] as Set).outcome()
    }

    /** A one-stage law: the build stage on {@code executorType}, with one judge check. */
    private void law(String executorType, String executorSettings, String judgeSettings) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - build\n')
        write('stages/build/instructions.md', 'build it\n')
        write('stages/build/accept.md', 'criteria\n')
        def model = executorType == 'agent-cli' ? '  model: cli-model\n' : '  command: make build\n'
        write('stages/build/stage.yaml', """\
purpose: build
executor:
  type: ${executorType}
${model}${executorSettings}instructions: stages/build/instructions.md
verify:
  - type: judge
    criteriaFile: stages/build/accept.md
    model: judge-model
    votes: 1
${judgeSettings}advancement: auto
""")
    }

    private void write(String relative, String text) {
        Path target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }
}
