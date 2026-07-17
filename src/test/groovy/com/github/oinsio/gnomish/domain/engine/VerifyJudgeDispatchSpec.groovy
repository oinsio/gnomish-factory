package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter

/**
 * VerifyOrchestrator judge dispatch, task 4.4 — a Judge check dispatches to the JudgeVoting
 * majority loop and its verdict is what the CheckResult carries; every cast vote's per-model
 * token map surfaces in the run's JudgeUsage in vote order, including empty maps for votes
 * that reported nothing; the TaskContext reaches the voter unmodified (FR3, FR7, NFR-C1 of
 * add-stage-engine; FR9, NFR-C1, D4 of add-agent-executor).
 */
class VerifyJudgeDispatchSpec extends VerifyOrchestratorSpecBase {

    // FR3: a Judge check dispatches to the JudgeVoting majority loop and its verdict is
    //      what the CheckResult carries
    def "dispatches a Judge to the judge voting loop and passes its majority verdict through"() {
        given: 'a judge voter passing a 3-vote check on the first majority (Pass, Pass)'
        def judgeVoter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Pass(), [:]),
            new JudgeVoter.Vote(new Verdict.Pass(), [:])
        ])

        when: 'the single judge check is verified'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), judgeVoter).verify([judge(3)], CONTEXT, WORKSPACE, KEY)

        then: 'the check passed on the majority, casting only the two votes it needed'
        result.results[0].verdict instanceof Verdict.Pass
        judgeVoter.voteCount == 2
    }

    // NFR-C1, D4: every cast judge vote's token map surfaces in the run's JudgeUsage, in
    //             vote order, including an empty map for a vote that reported nothing
    def "aggregates per-vote judge token maps into the run's JudgeUsage, in vote order"() {
        given: 'a 3-vote check: a token-reporting Fail, an unreported Fail, then a Pass'
        def first = ['model-a': new TokenUsage(10, 20, 0, 0)]
        def judgeVoter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Fail([]), first),
            new JudgeVoter.Vote(new Verdict.Fail([]), [:]),
            new JudgeVoter.Vote(new Verdict.Pass(), ['model-a': new TokenUsage(1, 2, 0, 0)])
        ])

        when: 'the judge check is verified to its Fail majority'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), judgeVoter).verify([judge(3)], CONTEXT, WORKSPACE, KEY)

        then: 'two Fails reach the majority, so only the two cast votes maps are accounted'
        result.results[0].verdict instanceof Verdict.Fail
        judgeVoter.voteCount == 2
        result.judgeUsage.perVote() == [first, [:]]
    }

    // FR7: the TaskContext (with its decisions) reaches the judge voter unmodified
    def "threads the task context through to the judge voter"() {
        given: 'a context carrying a decision and a single-vote passing judge'
        def context = new TaskContext('TASK-9', 'title', 'body', [
            new Decision('ship it', 'build', 'alice', java.time.Instant.EPOCH)
        ])
        def judgeVoter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Pass(), [:])
        ])

        when: 'the judge check is verified'
        orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), judgeVoter).verify([judge(1)], context, WORKSPACE, KEY)

        then: 'the voter received the exact context the chain was given'
        judgeVoter.contexts == [context]
    }
}
