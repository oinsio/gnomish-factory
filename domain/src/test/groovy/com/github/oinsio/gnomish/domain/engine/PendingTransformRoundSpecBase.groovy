package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * Shared fixture of the pending {@code transform} round specs (FR10, FR11, NFR-O2, UX3 of
 * extract-agent-round-runner; design D11): the real engine over a fake {@code program} executor named
 * {@code remote}, which the run describes as declaring {@code poll} unless a spec says otherwise, on
 * virtual time with the factory's pending bounds of 1 minute and 1 hour; the stage executor records how
 * many pending writes preceded each request.
 */
abstract class PendingTransformRoundSpecBase extends Specification {

    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    static final def TOKEN = RoundToken.of('a' * 40)
    static final def BOUNDS = new PendingBounds(Duration.ofMinutes(1), Duration.ofHours(1))
    static final def STAGE = new StageDefinition('build', 'purpose', [], [],
    new StageDefinition.Executor(ExecutorType.PROGRAM, '', [:], Sandbox.none(), 'remote'), 'instructions.md',
    [
        VerifyChecks.builtin('files_exist', [:])
    ], new AutonomyLimits(2), AdvancementMode.AUTO)

    def clock = new VirtualClock()
    def sleeper = new BudgetedVirtualSleeper(clock, 200)
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()
    def checks = new ScriptedCheckRounds()
    def taken = 0

    /** The stage executor; records how many pending writes preceded each request it received. */
    def executor = new ScriptedExecutor() {
        final List<Integer> writesBefore = []

        @Override
        protected ExecutionResult respond(StageExecutor.Request request, PendingTurn turn) {
            writesBefore << persistence.pending.size()
            super.respond(request, turn)
        }
    }

    TaskOutcome run(Set<String> pollable = ['remote'] as Set) {
        def identity = { taken++; Optional.of(TOKEN) } as RoundIdentity
        def described = new DescribedExecutors(ExecutorVersions.none(), ExecutorCredentialNames.none(), pollable)
        def ports = new EnginePorts(CheckRouting.of(executor, checks, new ScriptedCheckRounds(), new ScriptedCheckRounds()),
                listener, persistence, VirtualTimeEquipment.on(clock, sleeper), AttemptDelivery.assumedDelivered(),
                described, identity, BOUNDS)
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(5), [STAGE]), CONTEXT,
        TaskState.atStageStart('build'), new FakeWorkspace(), ports)
    }

    static ExecutionResult pending(String key, Duration hint = null, long tokens = 0) {
        new ExecutionResult(new ExecutionResult.Pending(new PendingKey(key), hint, PendingState.RUNNING, null, null),
                null, usage(tokens), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), [])
    }

    static ExecutionResult completed(long tokens = 0) {
        ExecutionResult.completed(usage(tokens), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), [])
    }

    static ExecutorUsage usage(long tokens) {
        new ExecutorUsage(null, [], Unpriced.of(['m-1': new TokenUsage(tokens, 0, 0, 0)]))
    }
}
