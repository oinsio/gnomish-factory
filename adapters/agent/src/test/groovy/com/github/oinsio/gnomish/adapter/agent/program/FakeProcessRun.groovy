package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Path

/**
 * One {@link ProgramExecutor#start} whose process is a {@link FakeProcess}, handed to a host
 * environment through its {@link ProcessLaunch}: inside the launch, before the fake is handed back,
 * {@code files} writes what the program would — its result, answer and stderr files, at the paths
 * the factory named — so no feature rests on a thread order. The specs set the fields they vary.
 */
final class FakeProcessRun {

    Path workspace
    Path stderrParent
    List<Capability> capabilities = []
    ProgramBounds bounds = ProgramBounds.DEFAULTS
    TimeEquipment time = VirtualTimeEquipment.on(new VirtualClock())

    /** {@code files(resultPath, answerPath, stderrPath)}; the process is handed back after it ran. */
    ExecutorResult start(FakeProcess process, Closure files) {
        run({ ProcessBuilder builder, StdinSource stdin ->
            files(Path.of(builder.environment()['GNOMISH_RESULT_FILE']), Path.of(builder.environment()['GNOMISH_ANSWER_FILE']),
            builder.redirectError().file().toPath())
            process
        } as ProcessLaunch)
    }

    /** One start over {@code launch} itself. */
    ExecutorResult run(ProcessLaunch launch) {
        def environment = new HostTaskExecutionEnvironment(workspace, time.clock(), ChildEnvAllowlist.none(), launch)
        environment.materialize('gnomish/TASK-7', null)
        try {
            new ProgramExecutor(ProgramFixtures.declaration(['acme-program']), ProgramFixtures.describe(capabilities),
            [], environment, ProgramFixtures.equipment(stderrParent, bounds, time))
            .start(ProgramFixtures.request())
        } finally {
            environment.dispose()
        }
    }
}
