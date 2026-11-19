package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path

/**
 * The run's check dispatch for a spec that assembles an engine by hand, through its production owners
 * ({@code testing.md}, "Fixtures assemble through production owners"): the {@link ExecutorRegistry}
 * of the definition's checks over the built-ins' entries ({@link BuiltInEntriesFixture}), the {@link
 * RunLaw}'s pin-check over the law at {@code lawRoot}, and the {@link CheckRoundRoute} that hands the
 * engine's check rounds to it outside the stage chain (task 4.2f of
 * migrate-verify-to-executor-contract).
 *
 * <p>Test fixture; not production code, never PIT-mutated.
 */
final class CheckDispatchFixture {

    private CheckDispatchFixture() {}

    /** The check dispatch of a run of {@code definition}, its pins compared against the law at {@code lawRoot}. */
    static CheckDispatch dispatch(PipelineDefinition definition, Path lawRoot, BuiltInEntries builtIns = null) {
        def clock = new VirtualClock()
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock),
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'), LastRegisteredCommit.NONE)
        new CheckDispatch(ExecutorRegistry.of(DescribedLaws.none(), definition, PipelineLaw.ofContent([:]), equipment,
        builtIns ?: BuiltInEntriesFixture.of(clock), null), RunLaw.open(LawBinding.workingTree(lawRoot)).pinCheck())
    }

    /**
     * {@code stages} as the engine's one port in a container run of {@code described}'s law: a check
     * round goes to the dispatch whose programs bind their checks through {@code freshBoxes} — a box
     * per request — over {@code equipment}, the run's cell under its read-only role (task 5.3 of
     * migrate-verify-to-executor-contract).
     */
    static StageExecutor routedBoxed(StageExecutor stages, DescribedLaw described, PipelineDefinition definition,
            Path lawRoot, BuiltInEntries builtIns, ProgramRoundEquipment equipment, FreshBoxSource freshBoxes) {
        new CheckRoundRoute(stages, new CheckDispatch(ExecutorRegistry.of(described, definition,
                PipelineLaw.ofContent([:]), equipment, builtIns, freshBoxes),
                RunLaw.open(LawBinding.workingTree(lawRoot)).pinCheck()))
    }

    /** The check rounds of a run of {@code definition}: each bound by the dispatch at its request. */
    static StageExecutor checkRounds(PipelineDefinition definition, Path lawRoot) {
        def dispatch = dispatch(definition, lawRoot)
        return { StageExecutor.Request request ->
            dispatch.bind(request.check(), request.checkRef(), request.workspace()).execute(request)
        } as StageExecutor
    }

    /**
     * {@code stages} as the engine's one port, a check round going to the dispatch of {@code
     * definition} — over {@code builtIns} when a spec's check needs an operator scope of its own.
     */
    static StageExecutor routed(StageExecutor stages, PipelineDefinition definition, Path lawRoot,
            BuiltInEntries builtIns = null) {
        new CheckRoundRoute(stages, dispatch(definition, lawRoot, builtIns))
    }
}
