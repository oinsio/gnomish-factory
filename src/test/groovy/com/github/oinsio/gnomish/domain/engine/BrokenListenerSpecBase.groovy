package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * Shared fixture for the broken-listener specs — the static helpers and fakes that build
 * the non-trivial fail-then-pass pipeline and its port set, so a listener that throws on
 * every event can be proven harmless without duplicating ~50 lines across specs.
 *
 * <p>Implements FR12, NFR-O1 of add-stage-engine.
 */
abstract class BrokenListenerSpecBase extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])

    // A fresh port set: the throwing run and the silent control run each get their own
    // identical fakes so nothing but the listener's throwOnEvent flag differs.
    static EnginePorts portsFor(ScriptedExecutor executor, ScriptedBuiltinCheckRunner builtinRunner,
            InMemoryAttemptPersistence persistence, RecordingEventListener listener) {
        def clock = new VirtualClock()
        new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), listener, persistence,
                clock, new VirtualSleeper(clock))
    }

    static VerifyCheck.Builtin builtin(String name) {
        new VerifyCheck.Builtin(name, [:])
    }

    static StageDefinition stage(String name, List<VerifyCheck> verify) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(5), AdvancementMode.AUTO)
    }

    static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', new AutonomyLimits(5), stages.toList())
    }

    static ExecutionResult.Completed completed(int round) {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', round), []))
    }

    static Verdict.Fail fail(String message) {
        new Verdict.Fail([
            new Finding(message, null, null)
        ])
    }

    // A non-trivial pipeline: one AUTO stage, one check, executor Fails-then-Passes so many
    // events fire across two rounds and the run reaches Completed. Both the throwing run and
    // the control run script the same sequence into their own fresh fakes.
    protected static void scriptFailThenPass(ScriptedExecutor executor, ScriptedBuiltinCheckRunner builtinRunner) {
        builtinRunner.scripted << fail('nope')
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed(0)
        executor.scripted << completed(1)
    }
}
