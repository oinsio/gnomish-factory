package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR22 of define-executor-contract: a judge vote's result event is mapped like a stage round's, so
 * the {@code costUSD} its {@code modelUsage} reports reaches the vote and the {@code verdict} usage
 * entry the engine records for it — driven through {@link CliJudgeVoter} against the fake agent
 * binary's {@code judge-verdict-fail-fenced} scenario, whose one model reports a cost.
 */
class JudgeVoteCostSpec extends Specification {

    @TempDir
    Path workspaceDir

    // Scenario: executor/agent-executor / Judge vote carries cost
    def "FR22: a judge vote's verdict entry carries the cost its result event reported per model"() {
        given: 'a judge whose result event reports modelUsage with costUSD'
        Files.writeString(workspaceDir.resolve('criteria.md'), 'The output must be correct.')
        def law = PipelineLaw.ofContent(['criteria.md': 'The output must be correct.'])
        def voter = new CliJudgeVoter(FakeAgentSupport.propertiesFor('judge-verdict-fail-fenced'), new VirtualClock(), law)
        def check = new VerifyCheck.Judge('criteria.md', FakeAgentSupport.JUDGE_MODEL, [:], 1)

        when: 'the vote is cast and recorded the way the engine records it'
        def vote = voter.vote(check, FakeAgentSupport.defaultTaskContext(), new DirectoryWorkspace(workspaceDir))
        def entry = Participants.verdict(Participants.JUDGE_EXECUTOR, 0, vote.byModel())

        then: 'the vote carries the cost beside its model tokens'
        def priced = ['claude-fake-judge-1': new ModelUsage(new TokenUsage(160, 40, 0, 0), new BigDecimal('0.0012'))]
        vote.byModel() == priced

        and: 'the verdict entry carries that cost per model'
        entry.role() == ExecutorRole.VERDICT
        entry.byModel() == priced
    }
}
