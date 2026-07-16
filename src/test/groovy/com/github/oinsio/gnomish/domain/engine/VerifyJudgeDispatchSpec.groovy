package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter

/**
 * VerifyOrchestrator judge dispatch, task 4.4 — a Judge check dispatches to the JudgeVoting
 * majority loop and its verdict is what the CheckResult carries; each token-reporting vote
 * surfaces in the run's JudgeUsage in vote order (a null-token vote contributes none); the
 * TaskContext reaches the voter unmodified (FR3, FR7, NFR-C1). Implements FR3, FR7, NFR-C1
 * of add-stage-engine.
 */
class VerifyJudgeDispatchSpec extends VerifyOrchestratorSpecBase {

    // FR3: a Judge check dispatches to the JudgeVoting majority loop and its verdict is
    //      what the CheckResult carries
    def "dispatches a Judge to the judge voting loop and passes its majority verdict through"() {
        given: 'a judge voter passing a 3-vote check on the first majority (Pass, Pass)'
        def judgeVoter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Pass(), null),
            new JudgeVoter.Vote(new Verdict.Pass(), null)
        ])

        when: 'the single judge check is verified'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), judgeVoter).verify([judge(3)], CONTEXT, WORKSPACE, KEY)

        then: 'the check passed on the majority, casting only the two votes it needed'
        result.results[0].verdict instanceof Verdict.Pass
        judgeVoter.voteCount == 2
    }

    // NFR-C1: each token-reporting judge vote surfaces in the run's JudgeUsage, in vote
    //         order; a vote with null tokens contributes no entry
    def "aggregates per-vote judge tokens into the run's JudgeUsage, skipping unreported votes"() {
        given: 'a 3-vote check: a token-reporting Fail, a null-token Fail, then a Pass'
        def first = new TokenUsage(10, 20)
        def judgeVoter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Fail([]), first),
            new JudgeVoter.Vote(new Verdict.Fail([]), null),
            new JudgeVoter.Vote(new Verdict.Pass(), new TokenUsage(1, 2))
        ])

        when: 'the judge check is verified to its Fail majority'
        def result = orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), judgeVoter).verify([judge(3)], CONTEXT, WORKSPACE, KEY)

        then: 'two Fails reach the majority, so only their token-reporting votes are accounted'
        result.results[0].verdict instanceof Verdict.Fail
        judgeVoter.voteCount == 2
        result.judgeUsage.perVote() == [first]
    }

    // FR7: the TaskContext (with its decisions) reaches the judge voter unmodified
    def "threads the task context through to the judge voter"() {
        given: 'a context carrying a decision and a single-vote passing judge'
        def context = new TaskContext('TASK-9', 'title', 'body', [
            new Decision('ship it', 'build', 'alice', java.time.Instant.EPOCH)
        ])
        def judgeVoter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Pass(), null)
        ])

        when: 'the judge check is verified'
        orchestrator(new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), judgeVoter).verify([judge(1)], context, WORKSPACE, KEY)

        then: 'the voter received the exact context the chain was given'
        judgeVoter.contexts == [context]
    }
}
