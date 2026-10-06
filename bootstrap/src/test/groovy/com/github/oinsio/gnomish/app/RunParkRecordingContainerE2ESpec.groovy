package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Timeout

/**
 * FR1, FR2, FR10 of make-run-headless (design D8), container medium: the fresh container run and
 * the container resume record a stop as a park — {@code task.json} on the tip carries the outcome
 * ({@code lastEscalation} with an escalation), no pending marker is set, exactly one lifecycle
 * commit follows the last round commit, no cleanup commit was made — and keep the box stopped rather
 * than disposing it; the park reaches the real remote and the exit code is 10 or 11. The host
 * boundaries are {@code RunParkRecordingSpec}; the world is {@link ContainerParkSpecBase}.
 *
 * <p>Docker- and guard-image-gated, like every container E2E spec.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class RunParkRecordingContainerE2ESpec extends ContainerParkSpecBase {

    /** What every recorded container park leaves behind, whichever boundary recorded it. */
    private void assertParked(RunParkedException stop, Class<? extends TaskOutcomeDto> outcome, int exitCode) {
        def task = tipTask()
        assert outcome.isInstance(task.outcome())
        assert (task.lastEscalation() != null) == (outcome == TaskOutcomeDto.Escalated)
        // Design D8 (revised 2026-10-06): a manual run owes no tracker write, so the record carries no
        // marker at all — the key is absent, never set and then cleared.
        assert task.trackerWritePending() == null
        // Exactly one lifecycle commit follows the engine's last round commit: the park's outcome
        // commit, with no receipt commit behind it.
        def subjects = gitOutput(cloneDir, 'log', '--format=%s', branch()).readLines()
        assert subjects.takeWhile {
            it.startsWith('gnomish: task ')
        }.size() == 1
        assert !subjects.contains('gnomish: task write-confirmed')

        assert ContainerE2eDocker.containerExists(boxName())
        assert !ContainerE2eDocker.containerRunning(boxName())
        assert originTip() == localTip()
        assert new RunExitCodeMapper().getExitCode(stop) == exitCode
    }

    def "FR1, FR10: a fresh container run that escalates records the park, keeps the box stopped, exits 10"() {
        given:
        taskId = "PARK-CE-${System.nanoTime() % 100000}"

        when:
        freshRun(ParkPipelines.escalating('work'))

        then:
        def stop = thrown(RunParkedException)
        assertParked(stop, TaskOutcomeDto.Escalated, 10)
    }

    def "FR2, FR10: a fresh container run that reaches a checkpoint records the park, keeps the box stopped, exits 11"() {
        given:
        taskId = "PARK-CP-${System.nanoTime() % 100000}"

        when:
        freshRun(ParkPipelines.pausing('work', 'review'))

        then:
        def stop = thrown(RunParkedException)
        assertParked(stop, TaskOutcomeDto.Paused, 11)
    }

    def "FR1, FR10: a container resume that escalates records the park, keeps the box stopped, exits 10"() {
        given: 'a run killed at its escalation before the park was recorded'
        taskId = "PARK-RE-${System.nanoTime() % 100000}"
        when:
        freshRun(ParkPipelines.escalating('work'), RunKillPoint.BEFORE_PARK_COMMIT)
        then:
        thrown(RunKills.SimulatedKill)

        when: 'the resume reproduces the escalation — the attempt limit is already spent'
        resume(ParkPipelines.escalating('work'))

        then:
        def stop = thrown(RunParkedException)
        assertParked(stop, TaskOutcomeDto.Escalated, 10)
    }

    def "FR2, FR10: a container resume that reaches a checkpoint records the park, keeps the box stopped, exits 11"() {
        given: 'a run killed at its first checkpoint before the park was recorded'
        taskId = "PARK-RP-${System.nanoTime() % 100000}"
        when:
        freshRun(ParkPipelines.pausing('work', 'review'), RunKillPoint.BEFORE_PARK_COMMIT)
        then:
        thrown(RunKills.SimulatedKill)

        when: 'the resume continues from the recorded position and stops at the second checkpoint'
        resume(ParkPipelines.pausing('work', 'review'))

        then:
        def stop = thrown(RunParkedException)
        (stop.outcome() as TaskOutcome.Paused).passedStage() == 'review'
        assertParked(stop, TaskOutcomeDto.Paused, 11)
    }
}
