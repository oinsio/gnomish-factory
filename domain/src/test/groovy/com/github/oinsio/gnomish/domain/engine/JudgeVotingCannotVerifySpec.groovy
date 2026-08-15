package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter

/**
 * JudgeVoting, task 4.4 — the CannotVerify short-circuit and defensive-copy behavior
 * of the judge majority loop (FR3, NFR-C1): a single CannotVerify vote taints the whole
 * check regardless of the running tally, casting no further votes, while still accounting
 * the tokens of every vote cast up to it and returning an unmodifiable per-vote list.
 * Implements FR3, NFR-C1 of add-stage-engine.
 */
class JudgeVotingCannotVerifySpec extends JudgeVotingSpecBase {

    // FR3: a single CannotVerify vote taints the whole check to CannotVerify regardless
    //      of the other votes' verdicts (Pass then CannotVerify — the running Pass tally
    //      does not save the check)
    def "fails the whole check as CannotVerify on a single CannotVerify vote"() {
        given: 'a 3-vote check: Pass, CannotVerify, then a Fail that must never be requested'
        def voter = new ScriptedJudgeVoter([
            pass(),
            cannotVerify('service down', 'HTTP 503'),
            fail([
                new Finding('unreached', null, null)
            ])
        ])

        when: 'the majority loop runs'
        def result = voting(voter).vote(judge(3), CONTEXT, WORKSPACE)

        then: 'the whole check is CannotVerify, carrying that vote reason and details'
        result.verdict() instanceof Verdict.CannotVerify
        result.verdict().reason() == 'service down'
        result.verdict().details() == 'HTTP 503'

        and: 'it short-circuits: only two votes were cast, the third never requested'
        voter.voteCount == 2
    }

    // FR3: a CannotVerify on the very first vote short-circuits immediately — one vote cast
    def "fails as CannotVerify on the first vote, casting exactly one vote"() {
        given: 'a 3-vote check whose first vote cannot be graded'
        def voter = new ScriptedJudgeVoter([
            cannotVerify('unparseable', 'no verdict token in output'),
            pass(),
            pass()
        ])

        when: 'the majority loop runs'
        def result = voting(voter).vote(judge(3), CONTEXT, WORKSPACE)

        then: 'the check is CannotVerify with the first vote reason and details'
        result.verdict() instanceof Verdict.CannotVerify
        result.verdict().reason() == 'unparseable'
        result.verdict().details() == 'no verdict token in output'

        and: 'exactly one vote was cast'
        voter.voteCount == 1
    }

    // FR3, NFR-C1, D4: tokens reported before and by the CannotVerify vote itself are
    //                  still captured in perVote up to the short-circuit point
    def "captures per-vote token maps up to and including the CannotVerify vote"() {
        given: 'a 3-vote check: Pass with tokens, CannotVerify with tokens, then unreached'
        def t1 = ['model-a': new TokenUsage(10, 20, 0, 0)]
        def t2 = ['model-a': new TokenUsage(30, 40, 0, 0)]
        def voter = new ScriptedJudgeVoter([
            pass(t1),
            cannotVerify('timeout', 'poll deadline exceeded', t2),
            pass(['model-a': new TokenUsage(99, 99, 0, 0)])
        ])

        when: 'the majority loop runs'
        def result = voting(voter).vote(judge(3), CONTEXT, WORKSPACE)

        then: 'the check is CannotVerify and both cast votes token maps are accounted, in order'
        result.verdict() instanceof Verdict.CannotVerify
        result.perVote() == [t1, t2]

        and: 'no further vote was cast'
        voter.voteCount == 2
    }

    // FR3: a vote's per-vote result list is defensively copied and unmodifiable
    def "returns an unmodifiable per-vote token list"() {
        given: 'a single passing vote reporting tokens'
        def voter = new ScriptedJudgeVoter([
            pass(['model-a': new TokenUsage(1, 1, 0, 0)])
        ])

        when: 'the check runs and its perVote list is mutated'
        def result = voting(voter).vote(judge(1), CONTEXT, WORKSPACE)
        result.perVote().add(['model-b': new TokenUsage(2, 2, 0, 0)])

        then: 'the copy rejects mutation'
        thrown(UnsupportedOperationException)
    }
}
