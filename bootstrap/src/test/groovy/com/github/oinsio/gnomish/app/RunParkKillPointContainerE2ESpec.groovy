package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Timeout

/**
 * NFR-R3 of make-run-headless (design D8, "Crash consistency of the park"), container medium: the
 * two kill windows after a park's outcome commit, for an {@code AttemptsExhausted} escalation and a
 * {@code paused} checkpoint — after the commit, before its push ({@link RunKillPoint#AFTER_PARK_COMMIT});
 * after the push, before the box keep ({@link RunKillPoint#AFTER_PARK_PUSH}). Per window, against the
 * real remote of {@link ContainerParkSpecBase}: the frozen state is the shape the design table names
 * (parked locally with origin behind; parked on origin with the box still running), the recovery
 * owner converges it, a second recovery pass over the converged state changes nothing durable, and
 * the transition — a {@code --resume} — then finds the parked task and ends on the same kind of park
 * (FR3, FR5).
 *
 * <p>The recovery owners, as the design table names them. After the commit: the resume-start
 * reconciliation {@code ContainerResumeRunner#run} performs before anything else — locate the branch,
 * push what origin lacks — run here on its own because the continuation behind it is the transition
 * (a {@code --resume} over {@code AttemptsExhausted} runs a round, over {@code paused} it continues).
 * After the push: the box keep itself, re-driven through the production {@code keepStopped} the next
 * terminal boundary runs — the startup sweep is age-governed and leaves a young box alone, and a
 * resume's reattach reuses a running box as it is, so the keep is what converges this window.
 *
 * <p>The window before the outcome commit is the host spec's ({@code RunParkKillPointSpec}) for
 * {@code AttemptsExhausted}; for the other stops it is owned by the follow-up change {@code
 * make-checkpoint-gate-durable}. Docker- and guard-image-gated, like every container E2E spec.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class RunParkKillPointContainerE2ESpec extends ContainerParkSpecBase {

    def "NFR-R3: a #kind park killed after its outcome commit, before the push, is delivered by the resume-start reconciliation, twice over"() {
        given:
        taskId = "KILL-C1-${System.nanoTime() % 100000}"

        when: 'the run dies with the park committed in the factory clone and its push never reaching origin'
        freshRun(pipeline, RunKillPoint.AFTER_PARK_COMMIT)

        then: 'the frozen shape: parked locally, origin behind, the box never stopped'
        thrown(RunKills.SimulatedKill)
        shape() == 'Parked'
        dto.isInstance(tipTask().outcome())
        originBehind()
        ContainerE2eDocker.containerRunning(boxName())

        when: 'the recovery owner runs: the reconciliation every container resume starts with'
        resumeStartReconciliation()
        def afterFirst = fingerprint()

        then: 'origin carries the park'
        originTip() == localTip()

        when: 'the same recovery runs again over the converged state'
        resumeStartReconciliation()

        then: 'nothing durable changed'
        fingerprint() == afterFirst

        when: 'the transition itself: the operator resumes the parked task'
        resume(pipeline)

        then: 'the resume finds the park and ends on one of the same kind, on both replicas'
        def stop = thrown(RunParkedException)
        reproduced(stop.outcome(), dto)
        shape() == 'Parked'
        originTip() == localTip()

        where:
        kind | pipeline | dto
        'escalated' | ParkPipelines.escalating('work') | TaskOutcomeDto.Escalated
        'paused' | ParkPipelines.pausing('work', 'review') | TaskOutcomeDto.Paused
    }

    def "NFR-R3: a #kind park killed after its push, before the box keep, is kept stopped by the next keep, twice over"() {
        given:
        taskId = "KILL-C2-${System.nanoTime() % 100000}"

        when: 'the run dies with the park on origin and the box still running'
        freshRun(pipeline, RunKillPoint.AFTER_PARK_PUSH)

        then: 'the frozen shape: parked on both replicas, the box running'
        thrown(RunKills.SimulatedKill)
        shape() == 'Parked'
        dto.isInstance(tipTask().outcome())
        originTip() == localTip()
        ContainerE2eDocker.containerRunning(boxName())

        when: 'the recovery owner runs: the keep the dead run never reached'
        directSupport(pipeline).keepStopped()
        def afterFirst = fingerprint()

        then: 'the box is stopped and kept; nothing on the branch moved'
        ContainerE2eDocker.containerExists(boxName())
        !ContainerE2eDocker.containerRunning(boxName())
        originTip() == localTip()

        when: 'the same keep runs again over the stopped box'
        directSupport(pipeline).keepStopped()

        then: 'nothing changed'
        fingerprint() == afterFirst

        when: 'the transition itself: the operator resumes the parked task'
        resume(pipeline)

        then: 'the resume finds the park and ends on one of the same kind, on both replicas'
        def stop = thrown(RunParkedException)
        reproduced(stop.outcome(), dto)
        shape() == 'Parked'
        originTip() == localTip()

        where:
        kind | pipeline | dto
        'escalated' | ParkPipelines.escalating('work') | TaskOutcomeDto.Escalated
        'paused' | ParkPipelines.pausing('work', 'review') | TaskOutcomeDto.Paused
    }

    /** The three resume-start calls {@code ContainerResumeRunner#run} makes before it reads the branch. */
    private void resumeStartReconciliation() {
        def branches = TaskGitFixture.real().branches()
        branches.harden(cloneDir)
        assert branches.ensureLocalTaskBranch(cloneDir, taskId)
        branches.reconcileRemote(cloneDir, taskId, 'resume-start')
    }

    /**
     * The production support of this task, as the next terminal boundary would build it — beside the
     * bundle's own tenure record, as {@link ContainerParkSpecBase#freshRun} builds it (FR5 of
     * fix-claim-epoch-fence); on the {@code run} path that record is simply never filled.
     */
    private ContainerRunSupport directSupport(PipelineDefinition pipeline) {
        ContainerSupportFixture.direct(cloneDir, taskId, segments(pipeline), sandbox(),
                testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY), OwnershipMode.MANUAL,
                TaskGitFixture.real().epochs())
    }

    /** The branch shape's label, read through the production classifier over the factory clone's ref. */
    private String shape() {
        TaskGitFixture.real().branches().classifyShape(cloneDir, taskId).label()
    }

    /** Origin holds a strict ancestor of the local tip: the same line, short of what the dead run committed. */
    private boolean originBehind() {
        def remote = originTip()
        remote != localTip() && gitExitCode(cloneDir, 'merge-base', '--is-ancestor', remote, localTip()) == 0
    }

    /** Everything a second recovery pass must leave untouched: both replicas' tips, the commits, the box state. */
    private Map fingerprint() {
        [
            local: localTip(),
            origin: originTip(),
            subjects: gitOutput(cloneDir, 'log', '--format=%s', branch()).readLines(),
            boxRunning: ContainerE2eDocker.containerRunning(boxName()),
        ]
    }

    /**
     * The resume ended on the recorded kind of park again: the spent limit for an escalation (FR3), a
     * checkpoint for a pause (FR5) — not merely some escalation, which a failed round would also be.
     */
    private static boolean reproduced(TaskOutcome outcome, Class<? extends TaskOutcomeDto> kind) {
        kind == TaskOutcomeDto.Escalated
                ? outcome instanceof TaskOutcome.Escalated
                && ((TaskOutcome.Escalated) outcome).report() instanceof EscalationReport.AttemptsExhausted
                : outcome instanceof TaskOutcome.Paused
    }
}
