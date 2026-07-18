package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import spock.lang.Specification

/**
 * Abstract port contract for
 * {@link com.github.oinsio.gnomish.domain.engine.port.JudgeVoter}: the behavioural
 * guarantees the engine's majority loop relies on from ANY judge adapter when it
 * casts one vote and reads back a {@link JudgeVoter.Vote}. A concrete subclass binds
 * an adapter-under-test through the {@link #arrange} seam, which builds the voter for
 * a target verdict-and-tokens shape, casts one vote against the supplied
 * {@link TaskContext}, and returns the produced {@link VoteOutcome}; an unproducible
 * shape returns {@link Optional#empty} and the row is recorded and skipped.
 *
 * <p>FR14 of add-manual-run: interactive and real adapters pass the same
 * port-contract suites as the fakes (metric M2). Underlying obligations come from
 * FR3/FR7/D2 of add-stage-engine.
 */
abstract class JudgeVoterContract extends Specification implements PortContractSupport {

    /** The verdict-and-tokens shapes a judge vote must be able to yield. */
    enum VoteShape {
        PASS_WITH_TOKENS, PASS_WITHOUT_TOKENS, FAIL, CANNOT_VERIFY
    }

    /** What a vote arrangement produced: the vote plus the context the voter observed. */
    static record VoteOutcome(JudgeVoter.Vote vote, TaskContext observedContext) {}

    /**
     * The arrangement seam: build the voter-under-test so its vote matches the given
     * {@code shape}, cast one vote against {@code context}, and return the produced
     * {@link VoteOutcome} — carrying both the vote and the {@link TaskContext} the
     * voter actually observed (so the FR7 pass-through can be asserted); or return
     * {@link Optional#empty} to declare the shape unproducible.
     *
     * @param shape which verdict-and-tokens shape the row needs
     * @param context the task context the engine hands the voter this round
     * @return the produced outcome, or empty when unproducible
     */
    protected abstract Optional<VoteOutcome> arrange(VoteShape shape, TaskContext context)

    private static TaskContext sampleContext() {
        new TaskContext('TASK-1', 'title', 'body', [])
    }

    // FR14: vote returns a non-null Vote whose verdict is non-null (FR3)
    def "vote returns a non-null Vote with a non-null verdict"() {
        given: 'a voter arranged to pass with reported tokens'
        def outcome = arrange(VoteShape.PASS_WITH_TOKENS, sampleContext())
        assumeProducible(outcome, 'JudgeVoter', 'Vote(Pass, tokens)')

        expect: 'a non-null Vote whose verdict is non-null comes back'
        outcome.get().vote() != null
        outcome.get().vote().verdict() != null
    }

    // FR14: the vote's verdict can be a Pass, and tokens may be present (FR3, NFR-C1)
    def "a vote can be Pass with reported tokens"() {
        given: 'a voter arranged to pass with reported tokens'
        def outcome = arrange(VoteShape.PASS_WITH_TOKENS, sampleContext())
        assumeProducible(outcome, 'JudgeVoter', 'Vote(Pass, tokens)')

        expect: 'the verdict is a Pass and the tokens are carried'
        outcome.get().vote().verdict() instanceof Verdict.Pass
        !outcome.get().vote().tokensByModel().isEmpty()
        outcome.get().vote().tokensByModel().values().every { it instanceof TokenUsage }
    }

    // FR14: tokensByModel may be empty — a vote may report no token counts (NFR-C1, D4)
    def "a vote can be produced with an empty tokensByModel map"() {
        given: 'a voter arranged to pass without reporting tokens'
        def outcome = arrange(VoteShape.PASS_WITHOUT_TOKENS, sampleContext())
        assumeProducible(outcome, 'JudgeVoter', 'Vote(Pass, no tokens)')

        expect: 'the tokens are absent, distinct from a fabricated zero'
        outcome.get().vote().tokensByModel().isEmpty()
    }

    // FR14: the vote's verdict can be a Fail (FR3)
    def "a vote can be Fail"() {
        given: 'a voter arranged to fail the artifact'
        def outcome = arrange(VoteShape.FAIL, sampleContext())
        assumeProducible(outcome, 'JudgeVoter', 'Vote(Fail)')

        expect: 'the verdict is a Fail'
        outcome.get().vote().verdict() instanceof Verdict.Fail
    }

    // FR14: the vote's verdict can be a CannotVerify with a non-blank reason (FR3, NFR-O1)
    def "a vote can be CannotVerify with a non-blank reason"() {
        given: 'a voter arranged so no verdict can be obtained'
        def outcome = arrange(VoteShape.CANNOT_VERIFY, sampleContext())
        assumeProducible(outcome, 'JudgeVoter', 'Vote(CannotVerify)')

        expect: 'the verdict is a CannotVerify the escalation report can name'
        outcome.get().vote().verdict() instanceof Verdict.CannotVerify
        !outcome.get().vote().verdict().reason().isBlank()
    }

    // FR14: the voter receives and can read the TaskContext it was handed (FR7)
    def "the voter receives the TaskContext it was handed"() {
        given: 'a distinctive task context'
        def context = new TaskContext('TASK-JUDGE', 'grade me', 'the body', [])

        and: 'a voter arranged to pass, observing that context'
        def outcome = arrange(VoteShape.PASS_WITH_TOKENS, context)
        assumeProducible(outcome, 'JudgeVoter', 'Vote(Pass, tokens)')

        expect: 'the voter observed exactly the context the engine handed it'
        outcome.get().observedContext() == context
    }
}
