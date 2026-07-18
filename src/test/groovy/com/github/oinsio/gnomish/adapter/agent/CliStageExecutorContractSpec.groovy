package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.port.contract.StageExecutorContract
import java.nio.file.Files

/**
 * FR15, M2 of add-agent-executor: {@link CliStageExecutor} passes the same
 * {@link StageExecutorContract} suite as the fake and interactive adapters,
 * driven against the fake agent binary through the real
 * {@code ProcessBuilder}/pipes/exit-code path (design D11).
 *
 * <p>Unlike {@code InteractiveStageExecutorContractSpec}, which reuses the
 * contract's fixed sample request unchanged because {@link
 * com.github.oinsio.gnomish.adapter.console.StageBriefing} degrades a
 * non-{@link DirectoryWorkspace} marker Workspace to a placeholder, {@link
 * CliStageExecutor} hard-requires a real {@link DirectoryWorkspace} with a
 * readable {@code instructionsRef} file — an unreadable control file is
 * deliberately an infrastructure failure here (FR13), not a placeholder. A
 * thin request-rewriting delegate swaps in a real temp-directory workspace
 * and a real instructions file before calling the actual adapter, so the
 * suite still exercises {@link CliStageExecutor#execute} itself, not a stub.
 */
class CliStageExecutorContractSpec extends StageExecutorContract {

    @Override
    protected Optional<StageExecutor> arrange(ExecutorVariant variant) {
        String scenario = switch (variant) {
                    case ExecutorVariant.COMPLETED -> 'plain-round'
                    case ExecutorVariant.DECISION_NEEDED -> 'decision-needed'
                }
        def workspaceDir = Files.createTempDirectory('cli-stage-executor-contract')
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
        def properties = FakeAgentSupport.propertiesFor(scenario)
        def real = new CliStageExecutor(properties, new VirtualClock())
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
            def substituted = new Request(request.context(), request.stage(), workspace, request.attempt(), request.feedback())
            delegate.execute(substituted)
        }
    }
}
