package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * The engine's one {@link StageExecutor} port as a run's assembly presents it (design D18 of
 * migrate-verify-to-executor-contract, as amended 2026-11-12): a request naming a check goes to the
 * {@link #checks} executor, every other request to the {@link #stages} executor — the fixture
 * twin of the production route, which the engine specs cannot reach from {@code :domain}.
 *
 * <p>Test fake; not production code, never PIT-mutated.
 */
class CheckRouting implements StageExecutor {

    /** Where a stage round goes. */
    final StageExecutor stages

    /** Where a check round goes. */
    final StageExecutor checks

    CheckRouting(StageExecutor stages, StageExecutor checks) {
        this.stages = stages
        this.checks = checks
    }

    /** {@code stages} with {@code checks} answering its check rounds. */
    static CheckRouting of(StageExecutor stages, StageExecutor checks = new ScriptedCheckRounds()) {
        new CheckRouting(stages, checks)
    }

    /**
     * {@code stages} with its check rounds answered by executor name, the way the run's registry
     * resolves them: a {@code files-exist} check by {@code builtins}, a {@code shell} check by
     * {@code commands}, a check of the agent entry {@code claude} — an agent judge check's round
     * (design D2 of extract-agent-round-runner) — by {@code judges}, any other executor's check by
     * {@code others}; {@code judges} defaults to {@code others}.
     */
    static CheckRouting of(StageExecutor stages, StageExecutor builtins, StageExecutor commands,
            StageExecutor others = new ScriptedCheckRounds(), StageExecutor judges = others) {
        new CheckRouting(stages, byExecutor(builtins, commands, others, judges))
    }

    /** Check rounds answered by executor name, as {@link #of(StageExecutor, StageExecutor, StageExecutor, StageExecutor, StageExecutor)}. */
    static StageExecutor byExecutor(StageExecutor builtins, StageExecutor commands,
            StageExecutor others = new ScriptedCheckRounds(), StageExecutor judges = others) {
        Closure<StageExecutor> target = { StageExecutor.Request request ->
            switch (request.check().executorName()) {
                case VerifyCheck.Executor.FILES_EXIST:
                return builtins
                case VerifyCheck.Executor.SHELL:
                return commands
                case BuiltInExecutors.CLAUDE:
                return judges
                default:
                return others
            }
        }
        new StageExecutor() {
                    @Override
                    ExecutionResult execute(StageExecutor.Request request) {
                        target(request).execute(request)
                    }

                    @Override
                    ExecutionResult poll(StageExecutor.Request request, PendingTurn turn) {
                        target(request).poll(request, turn)
                    }
                }
    }

    @Override
    ExecutionResult execute(StageExecutor.Request request) {
        request.check() == null ? stages.execute(request) : checks.execute(request)
    }

    @Override
    ExecutionResult poll(StageExecutor.Request request, PendingTurn turn) {
        request.check() == null ? stages.poll(request, turn) : checks.poll(request, turn)
    }
}
