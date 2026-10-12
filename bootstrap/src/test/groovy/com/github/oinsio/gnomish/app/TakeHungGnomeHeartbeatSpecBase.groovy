package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A hung gnome under a live instance (FR1 of add-claim-heartbeat; the {@code claim-heartbeat}
 * capability, "Heartbeat maintains the claim in place"): claim liveness answers "is the holder
 * process alive", not "is the work progressing". The gnome is the {@code hangs-forever} fake agent,
 * which never answers; the beats go on landing while it hangs, and what ends the round is the
 * stage's own {@code roundTimeout} — the executor kills the process and the engine escalates the
 * task as a round that could not execute, never the lease protocol reaping it.
 *
 * <p>The {@code roundTimeout} is real process time, the executor's own deadline: it is short, and
 * the round is only observed in flight before it, never timed. The assembly is {@link
 * TakeHeartbeatFixtureBase}'s; a subclass inside {@code adapter.tracker} fills the two seams.
 *
 * <p>Implements FR1 of add-claim-heartbeat.
 */
abstract class TakeHungGnomeHeartbeatSpecBase extends TakeHeartbeatFixtureBase {

    /** Long enough for two driven beats to land while the round hangs; short enough to end the spec. */
    private static final int ROUND_TIMEOUT_SECONDS = 5

    /** @return whether the tracker's thread for {@code ref} records a reaper's stale-claim removal */
    abstract boolean reaped(Tracker tracker, TaskRef ref)

    @Override
    protected String stageSettings() {
        "  settings:\n    roundTimeout: ${ROUND_TIMEOUT_SECONDS}\n"
    }

    // Scenario: lifecycle/claim-heartbeat / A hung gnome under a live instance keeps its claim
    def "FR1: a gnome that hangs keeps its beaten claim, and the stage's roundTimeout ends the round"() {
        given: 'a Ready task and a gnome that never answers'
        def command = newCommand(FakeAgentSupport.propertiesFor('hangs-forever'))
        def appArgs = args('take', 'PROJ-1', "--dir=$projectDir")
        def executor = Executors.newSingleThreadExecutor()

        when: 'take runs on another thread, and two beats are driven while the gnome hangs'
        Future<?> run = executor.submit({ command.run(appArgs) } as Callable)
        assert sleeper.awaitEntered(10000) != null: 'the beat thread never entered its first interval sleep'
        sleeper.releaseOne()
        assert sleeper.awaitEntered(10000) != null: 'the beat thread stopped before beat #2'
        sleeper.releaseOne()
        assert sleeper.awaitEntered(10000) != null: 'the beat thread stopped before parking after beat #2'

        then: 'both beats landed on the held claim while the hung round was still in flight'
        heartbeatCount(tracker, REF) == 2
        !run.isDone()
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Working

        when: 'the round runs into its roundTimeout'
        Throwable thrown = null
        try {
            run.get(60, TimeUnit.SECONDS)
        } catch (ExecutionException e) {
            thrown = e.cause
        }

        then: 'the executor ended the round: the task is parked for a human by the engine, not reaped'
        thrown instanceof TakeExitCodeException
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.AwaitingHuman
        !reaped(tracker, REF)

        cleanup:
        executor.shutdownNow()
    }
}
