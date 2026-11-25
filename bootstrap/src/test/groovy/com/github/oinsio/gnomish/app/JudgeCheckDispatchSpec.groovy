package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.runner.AgentCheckBinding
import com.github.oinsio.gnomish.adapter.agent.runner.FakeDialect
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files

/**
 * A {@code judge} check through the run's one check dispatch (design D2 of
 * extract-agent-round-runner): its round view resolves to the {@code claude} entry, and its request
 * carries the criteria prompt of the run's frozen law as its {@code verdict} role input; a judge
 * whose criteria cannot be read binds nothing.
 *
 * <p>FR4, M3 of extract-agent-round-runner.
 */
class JudgeCheckDispatchSpec extends JudgeDispatchSpecBase {

    // FR4, FR8 of add-agent-executor: the request is a read-only verdict whose role input is the
    //     criteria prompt from the frozen law, never the working copy's rewrite, with the block's model
    def "a judge's request carries the criteria of the frozen law, and its failing verdict fails the check"() {
        given: 'the gnome rewrote the acceptance criteria in its working copy'
        def copy = Files.createDirectories(root.resolve('copy'))
        Files.writeString(copy.resolve('criteria.md'), 'TAMPERED: everything passes.')
        def dialect = new FakeDialect()
        def runners = runners(dialect)
        def law = PipelineLaw.ofContent(['criteria.md': CRITERIA])
        def checks = AgentCheckBinding.onHost(runners, TestChildEnvironment.hostAllowlist(), launch)

        when:
        def result = run(dispatch(checks, runners, law), JudgeChecks.agent('criteria.md', 'opus'), new DirectoryWorkspace(copy))

        then: 'one launch, its verdict request read-only, its role input the law\'s criteria and the task'
        agentLaunches.size() == 1
        def sent = dialect.rendered.first()
        sent.role() == Role.VERDICT
        sent.policy() instanceof Policy.ReadOnly
        sent.settings().path('model').asText() == 'opus'
        stdins[0].contains(CRITERIA)
        stdins[0].contains('the widget body')
        !stdins[0].contains('TAMPERED')

        and: 'the failing verdict is the check\'s, with its findings'
        def check = derived(result) as Verdict.Fail
        check.findings()*.message() == ['missing spec', 'wrong name']
    }

    // FR13 of add-agent-executor: the criteria preflight runs ahead of the request — nothing is bound
    // Scenario: executor/agent-executor / Unreadable criteria stop the vote before spawn
    def "a judge whose criteria the frozen law could not read binds no executor and launches nothing"() {
        given:
        def runners = runners(new FakeDialect())
        FreshBoxSource boxes = { String commit, List<String> egress ->
            throw new AssertionError('no box is materialized for a refused judge')
        } as FreshBoxSource
        def checks = AgentCheckBinding.inFreshBoxes(runners, boxes)

        when:
        def result = run(dispatch(checks, runners, PipelineLaw.ofContent([:])), JudgeChecks.agent('missing.md', 'opus'),
        AttemptCommitWorkspaces.at('a' * 40))

        then:
        agentLaunches.isEmpty()
        def verdict = derived(result) as Verdict.CannotVerify
        verdict.reason().forLog().contains("'missing.md' is unreadable")

        when: 'the refused binding is polled (D11 of extract-agent-round-runner: a check\'s poll is a round of its own)'
        def judge = JudgeChecks.agent('missing.md', 'opus')
        def workspace = AttemptCommitWorkspaces.at('a' * 40)
        def round = request(judge, workspace)
        def polled = dispatch(checks, runners, PipelineLaw.ofContent([:])).bind(round.check(), round.checkRef(), workspace)
        .poll(round, new PendingTurn(new PendingKey('k-1'), PendingTurn.Origin.ISSUED))

        then: 'the poll answers the same refusal, and still nothing is launched'
        agentLaunches.isEmpty()
        (derived(polled) as Verdict.CannotVerify).reason() == verdict.reason()
    }
}
