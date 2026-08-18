package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The executor and executor-settings guards seen through the composition point: an
 * {@code api} executor is rejected at startup because only {@code agent-cli} is
 * supported so far (FR10), and an unrecognized or malformed settings key — on the
 * stage executor or on a judge check — is a located error naming the stage and the
 * offending key (FR11). Both fail the load before any dialog with a model or CLI is
 * opened (UX2), and both are aggregated like any other ConfigError into an immutable
 * list (D6/D7).
 *
 * <p>Implements FR10, FR11 (+ UX2) of load-pipeline-config.
 */
class PipelineLoaderExecutorSettingsSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

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
            "api executor is not yet supported; 'agent-cli' is the only supported executor type currently")
        ]
    }

    def "an agent-cli stage with an unrecognized settings key fails to load with a located error naming the stage and key (FR11/UX2/D7)"() {
        given: 'a valid tree except the build stage settings carry a typo\'d key'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - build\n')
        write('stages/build/stage.yaml', '''\
purpose: build
executor:
  type: agent-cli
  model: cli-model
  settings:
    allowedTols:
      - Read
instructions: stages/build/instructions.md
advancement: auto
''')
        write('stages/build/instructions.md', 'build it\n')

        when:
        def outcome = loadTree()

        then: 'startup fails with a located error naming the stage and the offending key, before any dialog'
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors() == [
            new ConfigError('stages/build/stage.yaml', 'executor.settings.allowedTols',
            "unrecognized settings key 'allowedTols'")
        ]
    }

    def "a judge check with a malformed maxTurns setting fails to load with a located error naming the check and key (FR11/UX2/D7)"() {
        given: 'a valid api-executor stage whose judge check settings carry a malformed maxTurns'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan the work
executor:
  type: api
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: judge
    criteriaFile: stages/plan/accept.md
    model: judge-model
    votes: 1
    settings:
      maxTurns: "five"
advancement: auto
''')
        write('stages/plan/instructions.md', 'plan it\n')
        write('stages/plan/accept.md', 'criteria\n')

        when:
        def outcome = loadTree()

        then: 'the judge check is validated even though its parent stage is api (and api itself is separately rejected, D6)'
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors() == [
            new ConfigError('stages/plan/stage.yaml', 'executor.type',
            "api executor is not yet supported; 'agent-cli' is the only supported executor type currently"),
            new ConfigError('stages/plan/stage.yaml', 'verify[0].settings.maxTurns',
            "malformed 'maxTurns': expected a number")
        ]
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
