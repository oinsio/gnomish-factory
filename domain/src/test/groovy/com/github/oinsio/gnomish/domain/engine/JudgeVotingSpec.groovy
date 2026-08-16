package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import java.time.Instant

/**
 * JudgeVoting, task 4.4 — the judge majority loop (FR3, FR7, NFR-C1): casts single
 * votes through the JudgeVoter up to the check's vote count, tallies them into one
 * verdict aggregating failing findings, early-stops the moment either verdict reaches
 * a majority, accounts each vote's tokens, and threads the TaskContext through to
 * every vote. Implements FR3, FR7, NFR-C1 of add-stage-engine.
 */
class JudgeVotingSpec extends JudgeVotingSpecBase {

    // FR3: a majority of failing votes fails the whole check, carrying every failing
    //      vote's findings aggregated in vote order (Fail, Pass, Fail on a 3-vote check)
    def "fails on a majority of failing votes, aggregating both failing votes' findings"() {
        given: 'a 3-vote check: Fail(f1), Pass, Fail(f2)'
        def f1 = new Finding('first problem', null, null)
        def f2 = new Finding('second problem', null, null)
        def voter = new ScriptedJudgeVoter([
            fail([f1]),
            pass(),
            fail([f2])
        ])

        when: 'the majority loop runs'
        def result = voting(voter).vote(judge(3), CONTEXT, WORKSPACE)

        then: 'the check fails carrying both failing votes findings, in vote order'
        result.verdict() instanceof Verdict.Fail
        result.verdict().findings() == [f1, f2]

        and: 'all three votes were cast (no earlier majority)'
        voter.voteCount == 3
    }

    // FR3, NFR-C1: two failing votes reach the majority of a 3-vote check, so the third
    //              vote is never requested
    def "stops requesting votes once a failing majority is reached"() {
        given: 'a 3-vote check whose first two votes fail'
        def f1 = new Finding('a', null, null)
        def f2 = new Finding('b', null, null)
        def voter = new ScriptedJudgeVoter([
            fail([f1]),
            fail([f2]),
            pass()
        ])

        when: 'the majority loop runs'
        def result = voting(voter).vote(judge(3), CONTEXT, WORKSPACE)

        then: 'the check fails on the two-vote majority with both findings'
        result.verdict() instanceof Verdict.Fail
        result.verdict().findings() == [f1, f2]

        and: 'the third vote was never requested'
        voter.voteCount == 2
    }

    // FR3, NFR-C1: two passing votes reach the majority of a 3-vote check, so the third
    //              vote is never requested
    def "stops requesting votes once a passing majority is reached"() {
        given: 'a 3-vote check whose first two votes pass'
        def voter = new ScriptedJudgeVoter([
            pass(),
            pass(),
            fail([
                new Finding('unused', null, null)
            ])
        ])

        when: 'the majority loop runs'
        def result = voting(voter).vote(judge(3), CONTEXT, WORKSPACE)

        then: 'the check passes on the two-vote majority'
        result.verdict() instanceof Verdict.Pass

        and: 'the third vote was never requested'
        voter.voteCount == 2
    }

    // NFR-C1, D4: every cast vote's token map surfaces in perVote in vote order,
    //             including an empty map for a vote that reported nothing
    def "captures per-vote token maps in vote order, including empty maps"() {
        given: 'a 3-vote check: Fail with tokens, Fail without, then a Pass (unreached)'
        def t1 = ['model-a': new TokenUsage(10, 20, 0, 0)]
        def voter = new ScriptedJudgeVoter([
            fail([new Finding('x', null, null)], t1),
            fail([new Finding('y', null, null)], [:]),
            pass(['model-a': new TokenUsage(99, 99, 0, 0)])
        ])

        when: 'the majority loop runs'
        def result = voting(voter).vote(judge(3), CONTEXT, WORKSPACE)

        then: 'only the two cast votes are accounted, each contributing its own map'
        voter.voteCount == 2
        result.perVote() == [t1, [:]]
    }

    // FR7: the TaskContext is threaded through to every vote unchanged
    def "threads the task context through to every vote"() {
        given: 'a context with a decision and a single passing vote'
        def context = new TaskContext('TASK-7', 't', 'b',
                [
                    new Decision('ok', 'build', 'bob', Instant.EPOCH)
                ])
        def voter = new ScriptedJudgeVoter([pass()])

        when: 'the single-vote check runs'
        voting(voter).vote(judge(1), context, WORKSPACE)

        then: 'the voter received the exact context'
        voter.contexts == [context]
    }

    // FR3: an even vote count that ties (Pass, Fail — neither reaches majority=2) falls
    //      through to the defensive after-loop tie resolution, resolved by pass >= fail
    def "resolves an even-votes tie to Pass via the after-loop path"() {
        given: 'a 2-vote check: one Pass then one Fail — a tie, majority is 2'
        def voter = new ScriptedJudgeVoter([
            pass(),
            fail([
                new Finding('tie', null, null)
            ])
        ])

        when: 'the majority loop runs both votes without an early majority'
        def result = voting(voter).vote(judge(2), CONTEXT, WORKSPACE)

        then: 'both votes were cast and the tie resolves to Pass (pass >= fail)'
        voter.voteCount == 2
        result.verdict() instanceof Verdict.Pass
    }
}
