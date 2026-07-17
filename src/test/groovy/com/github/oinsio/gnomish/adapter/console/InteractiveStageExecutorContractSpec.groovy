package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.port.contract.StageExecutorContract

/**
 * {@link InteractiveStageExecutor} against the abstract {@link
 * StageExecutorContract}: both sealed {@link
 * com.github.oinsio.gnomish.domain.engine.ExecutionResult} variants are
 * reachable through real dialog — empty Enter for {@code Completed}, {@code
 * ask} plus a question/options dialog for {@code DecisionNeeded} — so no row
 * is skipped. The contract's own {@code sampleRequest()} fixture is used
 * unchanged: {@link StageBriefing} degrades its control-file section to a
 * placeholder line for the contract's marker {@code Workspace}, so no real
 * {@code DirectoryWorkspace} is needed here.
 *
 * <p>Implements FR14, M2 of add-manual-run.
 */
class InteractiveStageExecutorContractSpec extends StageExecutorContract {

    @Override
    protected Optional<StageExecutor> arrange(StageExecutorContract.ExecutorVariant variant) {
        List<String> script = switch (variant) {
                    case StageExecutorContract.ExecutorVariant.COMPLETED -> ['']
                    case StageExecutorContract.ExecutorVariant.DECISION_NEEDED ->
                    [
                        'ask',
                        'which path?',
                        'a',
                        'b',
                        ''
                    ]
                }
        def io = new ScriptedConsoleIO(script)
        def console = new DialogConsole(io, { json -> 'status' })
        Optional.of(new InteractiveStageExecutor(console, new StageBriefing()))
    }
}
