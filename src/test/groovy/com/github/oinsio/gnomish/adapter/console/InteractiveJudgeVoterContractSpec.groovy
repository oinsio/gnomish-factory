package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.contract.JudgeVoterContract
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.TempDir

/**
 * {@link InteractiveJudgeVoter} against the abstract {@link
 * JudgeVoterContract}: {@code Pass} (always without reported tokens — a
 * human never reports token counts) and {@code Fail} are reachable through
 * real dialog answers. {@code CannotVerify} is NOT reachable — the
 * adapter's prompt only accepts {@code pass} / {@code fail} (see {@link
 * InteractiveJudgeVoter#ACCEPTED_ANSWERS}); there is no dialog path that
 * yields {@link com.github.oinsio.gnomish.domain.engine.Verdict.CannotVerify},
 * so that row declares itself unproducible rather than fabricate an
 * undocumented answer to force it green. {@code PASS_WITHOUT_TOKENS} is the
 * only Pass shape this adapter can ever produce, since a human vote never
 * carries tokens; {@code PASS_WITH_TOKENS} is therefore also unproducible.
 *
 * <p>Implements FR14, M2 of add-manual-run.
 */
class InteractiveJudgeVoterContractSpec extends JudgeVoterContract {

    @TempDir
    Path projectRoot

    private VerifyCheck.Judge sampleCheck() {
        Files.writeString(projectRoot.resolve('criteria.md'), 'Acceptance: the widget spins.')
        new VerifyCheck.Judge('criteria.md', 'gpt-5', [:], 1)
    }

    private DirectoryWorkspace sampleWorkspace() {
        new DirectoryWorkspace(projectRoot)
    }

    @Override
    protected Optional<JudgeVoterContract.VoteOutcome> arrange(
            JudgeVoterContract.VoteShape shape, TaskContext context) {
        if (shape == JudgeVoterContract.VoteShape.PASS_WITH_TOKENS
                || shape == JudgeVoterContract.VoteShape.CANNOT_VERIFY) {
            return Optional.empty()
        }
        List<String> script = switch (shape) {
                    case JudgeVoterContract.VoteShape.PASS_WITHOUT_TOKENS -> ['pass']
                    case JudgeVoterContract.VoteShape.FAIL -> [
                        'fail',
                        'criteria not met',
                        ''
                    ]
                }
        def io = new ScriptedConsoleIO(script)
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console)
        def vote = voter.vote(sampleCheck(), context, sampleWorkspace())
        Optional.of(new JudgeVoterContract.VoteOutcome(vote, context))
    }
}
