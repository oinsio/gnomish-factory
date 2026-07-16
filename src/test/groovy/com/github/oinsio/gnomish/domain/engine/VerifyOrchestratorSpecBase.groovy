package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * Shared fixture for the VerifyOrchestrator specs, task 4.1 — the verify chain (FR2/FR3):
 * runs a stage attempt's ordered verify list, emits a CheckStarted/CheckFinished pair per
 * executed check, times each against the injected Clock, and stops at the first non-Pass
 * check. This base holds the common check builders, port fakes and orchestrator factory the
 * per-concern chain specs share. Implements FR2, FR3 of add-stage-engine.
 */
abstract class VerifyOrchestratorSpecBase extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def KEY = new AttemptKey('TASK-1', 'build', 0)
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])

    static VerifyCheck.Builtin builtin(String name) {
        new VerifyCheck.Builtin(name, [:])
    }

    static VerifyCheck.Command command(String line) {
        new VerifyCheck.Command(line)
    }

    static VerifyCheck.External external(String checkId, Duration interval, Duration timeout) {
        new VerifyCheck.External(checkId, interval, timeout)
    }

    static VerifyCheck.Judge judge(int votes) {
        new VerifyCheck.Judge('criteria.md', 'model', [:], votes)
    }

    def listener = new RecordingEventListener()
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    VerifyOrchestrator orchestrator(ScriptedBuiltinCheckRunner builtinRunner,
            ScriptedCommandCheckRunner commandRunner,
            ScriptedExternalCheckClient externalClient = new ScriptedExternalCheckClient(),
            ScriptedJudgeVoter judgeVoter = new ScriptedJudgeVoter()) {
        new VerifyOrchestrator(builtinRunner, commandRunner,
                new ExternalPolling(externalClient, clock, sleeper),
                new JudgeVoting(judgeVoter), clock, listener)
    }
}
