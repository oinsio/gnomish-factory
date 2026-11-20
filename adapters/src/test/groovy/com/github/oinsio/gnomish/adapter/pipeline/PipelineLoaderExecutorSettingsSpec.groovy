package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The executor and executor-settings guards seen through the composition point: an
 * {@code api} executor is rejected at startup because only {@code agent-cli} is
 * supported so far (FR10), and settings that are no mapping, or hold a key with no
 * value, are located errors (FR11). They fail the load before any dialog with a model
 * or CLI is opened (UX2), and are aggregated like any other ConfigError into an
 * immutable list (D6/D7). What an agent's settings may hold is the Claude dialect's
 * schema, judged through the loader in {@code ClaudeSettingsLoadSpec} ({@code
 * :adapters:agent}; FR7 of extract-agent-round-runner).
 *
 * <p>Implements FR10, FR11 (+ UX2) of load-pipeline-config.
 */
class PipelineLoaderExecutorSettingsSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    // Scenario: cli/manual-run / Api stage rejected before any dialog
    def "a tree with an api-executor stage fails to load with a located error naming the stage, before any dialog (FR10/UX2/D6)"() {
        given: 'a valid tree except the plan stage declares an api executor'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan the work
executor:
  type: api
  model: plan-model
instructions: stages/plan/instructions.md
advancement: auto
''')
        write('stages/plan/instructions.md', 'plan it\n')

        when:
        def outcome = loadTree()

        then: 'startup fails with a located error naming the stage and the executor.type field'
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors() == [
            new ConfigError('stages/plan/stage.yaml', 'executor.type',
            "api executor is not yet supported; the supported executor types are 'agent-cli', 'program' and 'command'")
        ]
    }

    // Scenario: pipeline/pipeline-config / Settings that are not a mapping are rejected
    def "a stage whose settings is #shape fails to load with an error located at the stage's settings"() {
        given:
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - build\n')
        write('stages/build/stage.yaml', """\
purpose: build
executor:
  type: agent-cli
  model: cli-model
  settings: ${settings}
instructions: stages/build/instructions.md
advancement: auto
""")
        write('stages/build/instructions.md', 'build it\n')

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors().any {
            it.file() == 'stages/build/stage.yaml' && it.where().startsWith('executor.settings')
        }

        where:
        shape | settings
        'a string' | 'fast'
        'a list' | '[maxTurns, 5]'
    }

    // Scenario: pipeline/pipeline-config / Settings key with no value is rejected
    // UX2 of add-command-executor (design D10): a settings key written with no value is YAML null — a
    //     located error, never an exception
    def "an empty #key under #where fails to load with a located error, not an exception"() {
        given:
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - build\n')
        write('stages/build/instructions.md', 'build it\n')
        write('stages/build/accept.md', 'criteria\n')
        write('stages/build/stage.yaml', """\
purpose: build
executor:
  type: agent-cli
  model: cli-model
${executorSettings}instructions: stages/build/instructions.md
verify:
  - type: judge
    criteriaFile: stages/build/accept.md
    model: judge-model
    votes: 1
${judgeSettings}advancement: auto
""")

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors() == [
            new ConfigError('stages/build/stage.yaml', "${where}.${key}".toString(), "missing value for '${key}'".toString())
        ]

        where:
        where | key | executorSettings | judgeSettings
        'executor.settings' | 'roundTimeout' | '  settings:\n    roundTimeout:\n' | ''
        'executor.settings' | 'maxTurns' | '  settings:\n    maxTurns:\n' | ''
        'verify[0].settings' | 'roundTimeout' | '' | '    settings:\n      roundTimeout:\n'
    }

    def "the aggregated error list is immutable"() {
        given: 'any invalid tree'
        write('config.yaml', 'autonomy:\n  attemptLimit: 1\n')
        write('pipeline.yaml', 'stages: []\n')

        when:
        def outcome = loadTree()
        (outcome as LoadOutcome.Invalid).errors().add(new ConfigError('x', 'y', 'z'))

        then:
        thrown(UnsupportedOperationException)
    }
}
