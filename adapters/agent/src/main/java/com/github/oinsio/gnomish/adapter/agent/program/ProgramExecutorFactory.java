package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;

/**
 * The {@link RoundExecutorFactory} of one law-declared {@code program} executor (design D2 of
 * add-command-executor; FR10 of define-executor-contract): each round's request is built from the
 * frozen law ({@link ProgramRequests}), and each round's executor is a {@link ProgramExecutor} over
 * the program as the describe preflight left it, in the round's environment, with the run's equipment
 * — exactly what the round wrapper built itself before the wrapper was shared — over the run's table
 * of pending runs of the program, unscoped: a stage round has no subject (design D14 of
 * migrate-verify-to-executor-contract, as amended 2026-11-13).
 *
 * <p>Implements FR10 of define-executor-contract; FR8 of add-command-executor; FR3 of
 * migrate-verify-to-executor-contract.
 */
public final class ProgramExecutorFactory implements RoundExecutorFactory {

    private final PipelineLaw law;
    private final DescribedProgram program;
    private final ProgramRoundEquipment equipment;
    private final PendingRuns runs;

    /**
     * @param law the run's frozen law, the source of each round's instructions; never null
     * @param program the stage's executor as the describe preflight left it; never null
     * @param equipment the run's program equipment; never null
     * @param runs the run's table of pending runs of the program, built with its registry entry and
     *     shared by every binding of it (design D14 of migrate-verify-to-executor-contract, as amended
     *     2026-11-13); never null
     */
    public ProgramExecutorFactory(
            PipelineLaw law, DescribedProgram program, ProgramRoundEquipment equipment, PendingRuns runs) {
        this.law = law;
        this.program = program;
        this.equipment = equipment;
        this.runs = runs;
    }

    @Override
    public String name() {
        return program.declaration().name();
    }

    /**
     * {@inheritDoc}
     *
     * @throws com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException when the stage's
     *     instructions were unreadable when the law was frozen
     */
    @Override
    public ExecutorRequest request(StageExecutor.Request round) {
        return ProgramRequests.of(round, law, program.declaration(), equipment.bounds());
    }

    @Override
    public Executor executor(TaskExecutionEnvironment environment) {
        // A stage round has no subject and adopts against the round's last registered commit.
        return new ProgramExecutor(
                program.declaration(),
                program.describe(),
                program.credentials(),
                environment,
                equipment,
                PendingScope.unscoped(runs));
    }
}
