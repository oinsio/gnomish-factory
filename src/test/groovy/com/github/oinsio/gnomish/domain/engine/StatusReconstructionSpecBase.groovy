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
import java.time.Duration
import spock.lang.Specification

/**
 * Shared fixture for the status-reconstruction specs, task 6.3 — the NFR-O2 sufficiency
 * proof. Each spec drives {@code Engine.run} with a {@link RecordingEventListener} and
 * rebuilds a task's status view purely from the recorded {@link EngineEvent} stream (via
 * {@code StatusViewSupport.reconstruct}), asserting the rebuild equals the run's final
 * {@link TaskState} / {@link TaskOutcome}. This base holds the common ports and stage/usage
 * builders. Implements FR12, NFR-O2, UX2 of add-stage-engine.
 */
abstract class StatusReconstructionSpecBase extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def commandRunner = new ScriptedCommandCheckRunner()
    def externalClient = new ScriptedExternalCheckClient()
    def judgeVoter = new ScriptedJudgeVoter()
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    EnginePorts ports() {
        new EnginePorts(executor, builtinRunner, commandRunner, externalClient, judgeVoter,
                listener, persistence, clock, sleeper)
    }

    static VerifyCheck.Builtin builtin(String name) {
        new VerifyCheck.Builtin(name, [:])
    }

    static StageDefinition stage(String name, AdvancementMode advancement, List<VerifyCheck> verify) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(5), advancement)
    }

    static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', new AutonomyLimits(5), stages.toList())
    }

    static ExecutorUsage usage(long inTokens, long outTokens) {
        new ExecutorUsage(Duration.ofSeconds(1), [], new TokenUsage(inTokens, outTokens))
    }

    static ExecutionResult.Completed completed(String stageName, int round, ExecutorUsage usage) {
        new ExecutionResult.Completed(usage, new ToolTrace(new AttemptKey('TASK-1', stageName, round), []))
    }

    static Verdict.Fail fail(String message) {
        new Verdict.Fail([
            new Finding(message, null, null)
        ])
    }
}
