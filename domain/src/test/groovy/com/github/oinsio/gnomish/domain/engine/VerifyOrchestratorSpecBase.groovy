package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * Shared fixture for the VerifyOrchestrator specs, task 4.1 — the verify chain (FR2/FR3):
 * runs a stage attempt's ordered verify list, emits a CheckStarted/CheckFinished pair per
 * executed check, times each against the injected Clock, and stops at the first non-Pass
 * check. This base holds the common check builders, port fakes and orchestrator factory the
 * per-concern chain specs share: every executor check is a check round on the engine's port,
 * answered by the scripted check rounds of its executor's name (task 4.2f of
 * migrate-verify-to-executor-contract). Implements FR2, FR3 of add-stage-engine.
 */
abstract class VerifyOrchestratorSpecBase extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def KEY = new AttemptKey('TASK-1', 'build', 0)
    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])

    static VerifyCheck.Executor builtin(String name) {
        VerifyChecks.builtin(name, [:])
    }

    static VerifyCheck.Executor command(String line) {
        VerifyChecks.command(line)
    }

    static VerifyCheck.Executor external(String checkId, Duration interval, Duration timeout) {
        VerifyChecks.external(checkId, 'ci-platform', interval, timeout, VerifyCheck.TimeoutClass.QUALITY)
    }

    static VerifyCheck.Executor external(String checkId, Duration interval, Duration timeout,
            VerifyCheck.TimeoutClass timeoutClass) {
        VerifyChecks.external(checkId, 'ci-platform', interval, timeout, timeoutClass)
    }

    static VerifyCheck.Judge judge() {
        JudgeChecks.agent('criteria.md', 'model')
    }

    def listener = new RecordingEventListener()
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)
    def persistence = new InMemoryAttemptPersistence()

    /**
     * The chain over scripted check rounds by executor name: the two in-process built-ins, a judge's
     * check round (its block resolves to {@code claude}, design D2 of extract-agent-round-runner),
     * and every other executor.
     */
    VerifyOrchestrator orchestrator(ScriptedCheckRounds builtinRunner,
            ScriptedCheckRounds commandRunner,
            ScriptedCheckRounds externalClient = new ScriptedCheckRounds(),
            ScriptedCheckRounds judges = new ScriptedCheckRounds(),
            AttemptDelivery attemptDelivery = AttemptDelivery.assumedDelivered()) {
        def rounds = CheckRouting.byExecutor(builtinRunner, commandRunner, externalClient, judges)
        new VerifyOrchestrator(new PendingPoll(rounds, persistence, attemptDelivery, VirtualTimeEquipment.on(clock, sleeper),
                listener), DescribedExecutors.none(), clock, listener)
    }
}
