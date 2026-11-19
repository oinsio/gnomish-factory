package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks

/**
 * The identity spec of the contract → domain mapping's details (design D9, D19 of
 * migrate-verify-to-executor-contract): a {@code shell} check through the real host environment —
 * the engine, the run's check dispatch, the {@code shell} built-in, the one mapping, the host
 * persistence — whose command writes no findings file and exits red. The built-in writes the output
 * tail as the SARIF result's {@code properties.details}; read back from the state file at the round
 * commit, it is the finding's details, and the finding's message is the result's message unchanged.
 */
class ShellDetailsFlowSpec extends RoundFlowSpecBase {

    static final String TAIL = 'tail-of-the-red-command'

    static PipelineDefinition pipeline() {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
        'instructions.md', [
            VerifyChecks.command('echo ' + TAIL + '; exit 1')
        ], new AutonomyLimits(1), AdvancementMode.AUTO)
        new PipelineDefinition('1.0.0', new AutonomyLimits(1), [stage])
    }

    // Scenario: executor/wire-contract / SARIF details reach the finding's details
    // Scenario: verification/verdict-executors / A red command without a findings file carries its output tail
    def "FR5, D19: a red shell check's output tail written as properties.details is the recorded finding's details"() {
        given:
        def worktree = worktree()
        def definition = pipeline()

        when:
        def outcome = runHost(definition, worktree, FakeAgentSupport.propertiesFor('plain-round'),
                CheckDispatchFixture.checkRounds(definition, clone))

        then: 'the one attempt failed its check'
        outcome instanceof TaskOutcome.Escalated

        and: 'the state file at the round commit records the finding the mapping built'
        def state = HostVerificationKill.stateAt(clone, tip())
        def attempt = state.attempts().last()
        attempt.result() == AttemptRecord.Result.QUALITY_FAILURE
        def fail = attempt.checkResults()[0].verdict() as Verdict.Fail
        fail.findings().size() == 1
        fail.findings()[0].message() == 'command exited with status 1'
        fail.findings()[0].details().contains(TAIL)
    }
}
