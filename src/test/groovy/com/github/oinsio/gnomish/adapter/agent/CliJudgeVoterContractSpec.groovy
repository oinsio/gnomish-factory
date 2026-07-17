package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.contract.JudgeVoterContract
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files

/**
 * FR15, M2 of add-agent-executor: {@link CliJudgeVoter} passes the same
 * {@link JudgeVoterContract} suite as the fake and interactive adapters,
 * driven against the fake agent binary through the real {@code
 * ProcessBuilder}/pipes/exit-code path (design D11).
 *
 * <p>{@link JudgeVoterContract#arrange} hands each row a fixed sample {@code
 * TaskContext} but no workspace at all — {@link CliJudgeVoter} hard-requires
 * a real {@link DirectoryWorkspace} with a readable criteria file, so this
 * spec builds a fresh temp-directory workspace and criteria file per row
 * before invoking the real adapter, mirroring {@code
 * CliStageExecutorContractSpec}'s {@code WorkspaceSubstitutingExecutor}
 * precedent (there for a request-rewriting delegate; here inline, since
 * {@link JudgeVoterContract#arrange} takes no request to rewrite).
 */
class CliJudgeVoterContractSpec extends JudgeVoterContract {

    @Override
    protected Optional<VoteOutcome> arrange(VoteShape shape, TaskContext context) {
        String scenario = switch (shape) {
                    case VoteShape.PASS_WITH_TOKENS -> 'judge-verdict-pass'
                    case VoteShape.PASS_WITHOUT_TOKENS -> null
                    case VoteShape.FAIL -> 'judge-verdict-fail-fenced'
                    case VoteShape.CANNOT_VERIFY -> 'judge-verdict-garbage'
                }
        if (scenario == null) {
            return Optional.empty()
        }

        def workspaceDir = Files.createTempDirectory('cli-judge-voter-contract')
        Files.writeString(workspaceDir.resolve('criteria.md'), 'The output must be correct.')
        def check = new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1)
        def properties = FakeAgentSupport.propertiesFor(scenario)
        def voter = new CliJudgeVoter(properties, new VirtualClock())

        def vote = voter.vote(check, context, new DirectoryWorkspace(workspaceDir))
        Optional.of(new VoteOutcome(vote, context))
    }
}
