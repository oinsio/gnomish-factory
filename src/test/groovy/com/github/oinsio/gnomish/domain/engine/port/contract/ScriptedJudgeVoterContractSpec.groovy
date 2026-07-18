package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * The scripted
 * {@link com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter} is the
 * first concrete implementation of {@link JudgeVoterContract}: it produces every
 * verdict-and-tokens shape unchanged and records the {@link TaskContext} it was
 * handed, so no contract row is skipped. The interactive judge (add-manual-run §5.5)
 * later subclasses the SAME suite.
 *
 * <p>FR14 of add-manual-run: the scripted fake passes the extracted port-contract
 * suite unchanged (metric M2).
 */
class ScriptedJudgeVoterContractSpec extends JudgeVoterContract {

    private static JudgeVoter.Vote scriptedVote(JudgeVoterContract.VoteShape shape) {
        switch (shape) {
                    case JudgeVoterContract.VoteShape.PASS_WITH_TOKENS ->
                    new JudgeVoter.Vote(new Verdict.Pass(), ['model-a': new TokenUsage(100, 50, 0, 0)])
                    case JudgeVoterContract.VoteShape.PASS_WITHOUT_TOKENS ->
                    new JudgeVoter.Vote(new Verdict.Pass(), [:])
                    case JudgeVoterContract.VoteShape.FAIL ->
                    new JudgeVoter.Vote(new Verdict.Fail([
                        new Finding('criteria not met', null, null)
                    ]), [:])
                    case JudgeVoterContract.VoteShape.CANNOT_VERIFY ->
                    new JudgeVoter.Vote(new Verdict.CannotVerify('unparseable judge output', ''), [:])
                }
    }

    @Override
    protected Optional<JudgeVoterContract.VoteOutcome> arrange(
            JudgeVoterContract.VoteShape shape, TaskContext context) {
        def voter = new ScriptedJudgeVoter([scriptedVote(shape)])
        def check = new VerifyCheck.Judge('criteria.md', 'model', [:], 1)
        def vote = voter.vote(check, context, new FakeWorkspace())
        Optional.of(new JudgeVoterContract.VoteOutcome(vote, voter.contexts.first()))
    }
}
