package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * A {@code judge} check declares the stage's executor block (design D12 of
 * extract-agent-round-runner), loaded end to end through the real {@link PipelineLoader}: the
 * check's {@code executor:} is mapped by the one block mapping a stage's goes through and judged by
 * the same rules, located at {@code verify[<i>].executor.*}; no judge is defaulted; and the judge's
 * former own {@code votes}, {@code model} and {@code settings} keys are unknown fields of the
 * check, located at the check.
 *
 * <p>FR13, UX1 of extract-agent-round-runner; FR4 (votes refused at load).
 */
class PipelineLoaderJudgeBlockSpec extends Specification implements InvalidFixtureSupport {

    private static final String MANIFEST = 'stages/plan/stage.yaml'

    @TempDir
    Path root

    Path getRoot() {
        root
    }

    // Scenario: pipeline/pipeline-config / A judge check with an agent block is accepted
    def "FR13: a judge check with an agent-cli block loads, carrying the block a stage's maps to"() {
        given:
        writeJudge('''\
    executor:
      type: agent-cli
      model: m
      settings:
        maxTurns: 60
''')

        when:
        def outcome = PipelineLoader.load(root, [:], TrackerValidatorStub.acceptingGithubActions())

        then:
        outcome instanceof LoadOutcome.Loaded
        def stage = (outcome as LoadOutcome.Loaded).definition().stages()[0]
        def judge = stage.verify()[1] as VerifyCheck.Judge
        judge.criteriaFile() == 'stages/plan/accept.md'
        judge.executor() == new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [maxTurns: 60])

        and: 'the same executor type the stage block of the same spelling maps to'
        judge.executor().type() == stage.executor().type()
    }

    // Scenario: pipeline/pipeline-config / A judge check without an executor block is rejected
    def "FR13, UX1: a judge check with no executor block is a located error naming the check and executor"() {
        given:
        writeJudge('')

        expect:
        loadInvalid() == [
            new ConfigError(MANIFEST, 'verify[1].executor',
            "missing required field 'verify[1].executor'; a judge check declares the executor block that judges, as a stage declares its own")
        ]
    }

    // Scenario: pipeline/pipeline-config / A judge block without a model is rejected
    def "FR13, UX1: a judge agent-cli block without a model gets the stage block's missing-model error, at the check"() {
        given:
        writeJudge('''\
    executor:
      type: agent-cli
''')

        expect:
        loadInvalid() == [
            new ConfigError(MANIFEST, 'verify[1].executor.model',
            'missing required executor model; the model must be pinned in the manifest for every agent executor type (api, agent-cli)')
        ]
    }

    // Scenario: pipeline/pipeline-config / Removed judge keys are rejected
    def "UX1: a judge check declaring #key beside its executor block is refused as an unknown field of the check"() {
        given:
        writeJudge('''\
    executor:
      type: agent-cli
      model: m
''' + line)

        expect:
        loadInvalid() == [
            new ConfigError(MANIFEST, "verify[1].${key}".toString(), "unknown field '${key}'".toString())
        ]

        where:
        key | line
        'votes' | '    votes: 1\n'
        'model' | '    model: m\n'
        'settings' | '    settings:\n      maxTurns: 3\n'
    }

    // Scenario: pipeline/pipeline-config / Judge vote count must be positive and odd
    def "FR4: a judge check declaring votes: #votes is a located error identifying the check, whatever the count"() {
        given:
        writeJudge('''\
    executor:
      type: agent-cli
      model: m
    votes: ''' + votes + '\n')

        expect:
        loadInvalid() == [
            new ConfigError(MANIFEST, 'verify[1].votes', "unknown field 'votes'")
        ]

        where:
        votes << [0, 1, 2, 3]
    }

    /** The baseline tree whose plan stage ends with a judge check carrying {@code block}. */
    private void writeJudge(String block) {
        writeValidBaseline()
        write('stages/plan/accept.md', 'criteria\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan the work
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: command
    command: echo ok
  - type: judge
    criteriaFile: stages/plan/accept.md
''' + block + 'advancement: auto\n')
    }
}
