package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.port.contract.StageExecutorContract
import java.nio.file.Files

/**
 * FR15, M2 of add-agent-executor: the agent stage executor — the {@code claude} entry's stage half
 * through the shared round wrapper over the host round source ({@link ClaudeStages}; task 5.1 of
 * extract-agent-round-runner) — passes the same {@link StageExecutorContract} suite as the fake
 * adapter, driven against the fake agent binary through the real {@code ProcessBuilder}/pipes/exit-code
 * path (design D11).
 *
 * <p>The contract's fixed sample request carries a marker Workspace, but a real round needs a real
 * {@link DirectoryWorkspace}: a thin request-rewriting delegate swaps in a temp-directory workspace
 * before calling the real executor, so the suite still exercises the real round, not a stub.
 */
// Scenario: cli/manual-run / One suite, many adapters
class ClaudeStageContractSpec extends StageExecutorContract {

    @Override
    protected Optional<StageExecutor> arrange(ExecutorVariant variant) {
        String scenario = switch (variant) {
                    case ExecutorVariant.COMPLETED -> 'plain-round'
                    case ExecutorVariant.DECISION_NEEDED -> 'decision-needed'
                }
        def workspaceDir = Files.createTempDirectory('cli-stage-executor-contract')
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
        def properties = FakeAgentSupport.propertiesFor(scenario)
        def law = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])
        def real = ClaudeStages.executor(properties, new VirtualClock(), law)
        Optional.of(new WorkspaceSubstitutingExecutor(real, new DirectoryWorkspace(workspaceDir)))
    }

    /**
     * Delegates to a real {@link StageExecutor}, substituting {@code
     * workspace} for whatever the contract's fixed sample request carries —
     * the contract's marker {@code Workspace} is not a real {@link
     * DirectoryWorkspace} and cannot back a real CLI process's cwd or a real
     * control-file read.
     */
    private static final class WorkspaceSubstitutingExecutor implements StageExecutor {

        private final StageExecutor delegate
        private final DirectoryWorkspace workspace

        WorkspaceSubstitutingExecutor(StageExecutor delegate, DirectoryWorkspace workspace) {
            this.delegate = delegate
            this.workspace = workspace
        }

        @Override
        ExecutionResult execute(Request request) {
            delegate.execute(substituted(request))
        }

        @Override
        ExecutionResult poll(Request request, PendingTurn turn) {
            delegate.poll(substituted(request), turn)
        }

        private Request substituted(Request request) {
            new Request(request.context(), request.stage(), workspace, request.attempt(), request.feedback())
        }
    }
}
