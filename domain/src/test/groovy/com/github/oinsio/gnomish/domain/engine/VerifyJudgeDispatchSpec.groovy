package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * VerifyOrchestrator judge dispatch, task 4.4 — a Judge check dispatches to the JudgeVoting
 * majority loop and its verdict is what the CheckResult carries; every cast vote's per-model
 * token map surfaces as the run's verdict entries in vote order, including empty maps for votes
 * that reported nothing; the TaskContext reaches the voter unmodified (FR3, FR7, NFR-C1 of
 * add-stage-engine; FR9, NFR-C1, D4 of add-agent-executor).
 */
class VerifyJudgeDispatchSpec extends VerifyOrchestratorSpecBase {

    // FR3: a Judge check dispatches to the JudgeVoting majority loop and its verdict is
    //      what the CheckResult carries
    def "dispatches a Judge to the judge voting loop and passes its majority verdict through"() {
        given: 'a judge voter passing a 3-vote check on the first majority (Pass, Pass)'
        def judgeVoter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Pass(null, []), [:]),
            new JudgeVoter.Vote(new Verdict.Pass(null, []), [:])
        ])

        when: 'the single judge check is verified'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), judgeVoter).verify([judge(3)], CONTEXT, WORKSPACE, KEY)

        then: 'the check passed on the majority, casting only the two votes it needed'
        result.results[0].verdict instanceof Verdict.Pass
        judgeVoter.voteCount == 2
    }

    // NFR-C1, D4: every cast judge vote's token map surfaces as a verdict entry, in vote order,
    //             an unreported vote as an absent entry (FR15 of define-executor-contract)
    def "each cast judge vote becomes a verdict entry carrying its vote index, in vote order"() {
        given: 'a 3-vote check: a token-reporting Fail, an unreported Fail, then a Pass'
        def first = ['model-a': new TokenUsage(10, 20, 0, 0)]
        def judgeVoter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Fail([]), Unpriced.of(first)),
            new JudgeVoter.Vote(new Verdict.Fail([]), [:]),
            new JudgeVoter.Vote(new Verdict.Pass(null, []), Unpriced.of(['model-a': new TokenUsage(1, 2, 0, 0)]))
        ])

        when: 'the judge check is verified to its Fail majority'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), judgeVoter).verify([judge(3)], CONTEXT, WORKSPACE, KEY)

        then: 'two Fails reach the majority, so only the two cast votes maps are accounted'
        result.results[0].verdict instanceof Verdict.Fail
        judgeVoter.voteCount == 2
        result.votes == [
            Participants.verdict(Participants.JUDGE_EXECUTOR, 0, Unpriced.of(first)),
            Participants.verdict(Participants.JUDGE_EXECUTOR, 1, [:])
        ]
        result.votes*.provenance() == [
            UsageProvenance.REPORTED,
            UsageProvenance.ABSENT
        ]
    }

    // FR7: the TaskContext (with its decisions) reaches the judge voter unmodified
    def "threads the task context through to the judge voter"() {
        given: 'a context carrying a decision and a single-vote passing judge'
        def context = new TaskContext('TASK-9', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [
            new Decision('ship it', 'build', 'alice', java.time.Instant.EPOCH)
        ])
        def judgeVoter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Pass(null, []), [:])
        ])

        when: 'the judge check is verified'
        orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), judgeVoter).verify([judge(1)], context, WORKSPACE, KEY)

        then: 'the voter received the exact context the chain was given'
        judgeVoter.contexts == [context]
    }
}
