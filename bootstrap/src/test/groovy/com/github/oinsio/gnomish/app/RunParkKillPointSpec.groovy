package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R3 of make-run-headless (design D8, "Crash consistency of the park"), host medium: the kill
 * windows of a {@code gnomish run} park that the change owns. After the outcome commit and before
 * its push, for an {@code AttemptsExhausted} escalation and a {@code paused} checkpoint; and before
 * the outcome commit for {@code AttemptsExhausted} alone, whose spent limit is in the recorded state.
 * Per window, on a bare origin: the frozen state classifies to the shape the design table names
 * (through the production classifier), the recovery owner converges it — the park reaches origin —
 * a second recovery pass over the converged state changes nothing durable, and the transition itself,
 * a {@code --resume}, then finds the parked task (FR3–FR5).
 *
 * <p>The recovery owner of both windows is the resume bootstrap ({@code GitResumeRunner#bootstrap}):
 * its resume-start reconciliation pushes a local tip origin lacks, and a local line ahead of origin
 * continues from local. It runs here on its own, apart from the continuation that follows it, because
 * the continuation is the transition — a {@code --resume} over {@code AttemptsExhausted} resets the
 * attempts and runs a round, over {@code paused} it continues — and only the recovery is required to
 * be a no-op.
 *
 * <p>Deliberately not specced: the window before the outcome commit for {@code paused}, {@code
 * DecisionNeeded} and {@code CannotVerify}, where the stop is lost — the round commit already
 * persisted the advanced position, or a result nothing reads the stop back from. That is the
 * kill-point row of the follow-up change {@code make-checkpoint-gate-durable}, this change's named
 * dependency. The container medium is {@code RunParkKillPointContainerE2ESpec} (Docker-gated).
 */
class RunParkKillPointSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final String TASK_JSON = '.gnomish-task/task.json'

    @TempDir
    Path tempDir

    Path cloneDir
    Path origin
    RegisteredClone registeredClone

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(cloneDir)
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', origin.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
    }

    def "NFR-R3: a #kind park killed after its outcome commit, before the push, is delivered by the resume bootstrap, twice over"() {
        given:
        def taskId = "KILL-${kind}"
        def order = new RunOrder(cloneDir, null, pipeline, false)

        when: 'the run dies with the park committed locally and its push never reaching origin'
        freshRunner(RunKillPoint.AFTER_PARK_COMMIT).run(order, context(taskId), TaskState.atStageStart('build'))

        then: 'the frozen shape: parked locally, origin behind — on the line, without the outcome'
        thrown(RunKills.SimulatedKill)
        shape(cloneDir, taskId) == 'Parked'
        dto.isInstance(tipTask(cloneDir, taskId).outcome())
        originBehind(taskId)
        tipTask(origin, taskId).outcome() == null

        when: 'the recovery owner runs: the resume bootstrap, whose resume-start reconciliation pushes what origin lacks'
        resumeRunner().bootstrap(cloneDir, taskId)
        def afterFirst = fingerprint(taskId)

        then: 'origin carries the park'
        originTip(taskId) == localTip(taskId)
        dto.isInstance(tipTask(origin, taskId).outcome())

        when: 'the same recovery runs again over the converged state'
        resumeRunner().bootstrap(cloneDir, taskId)

        then: 'nothing durable changed'
        fingerprint(taskId) == afterFirst

        when: 'the transition itself: the operator resumes the parked task, without a --decision'
        // Without a --decision the escalation's attempts reset lives in memory only, so the rerun
        // exhausts the same limit and re-parks identically: recordOutcome then finds its document
        // already on the tip and makes no commit (FR10, design D8 "Idempotence"; task 2.6).
        resumeRunner().run(order, taskId, null)

        then: 'the resume finds a park of the recorded kind and ends on one, on both replicas'
        def stop = thrown(RunParkedException)
        reproduced(stop.outcome(), dto)
        shape(cloneDir, taskId) == 'Parked'
        originTip(taskId) == localTip(taskId)

        where:
        kind | pipeline | dto
        'escalated' | ParkPipelines.escalating() | TaskOutcomeDto.Escalated
        'paused' | ParkPipelines.pausing() | TaskOutcomeDto.Paused
    }

    def "NFR-R3: an AttemptsExhausted escalation killed before its outcome commit freezes the interrupted-run shape; the resume reproduces the park"() {
        given:
        def order = new RunOrder(cloneDir, null, ParkPipelines.escalating(), false)

        when:
        freshRunner(RunKillPoint.BEFORE_PARK_COMMIT).run(order, context('KILL-0'), TaskState.atStageStart('build'))

        then: 'rounds present, no outcome: the interrupted-run shape'
        thrown(RunKills.SimulatedKill)
        shape(cloneDir, 'KILL-0') == 'InProgress'
        tipTask(cloneDir, 'KILL-0').outcome() == null

        when: 'the resume bootstrap delivers the local line and, run again, changes nothing'
        resumeRunner().bootstrap(cloneDir, 'KILL-0')
        def afterFirst = fingerprint('KILL-0')
        resumeRunner().bootstrap(cloneDir, 'KILL-0')

        then:
        originTip('KILL-0') == localTip('KILL-0')
        fingerprint('KILL-0') == afterFirst

        when: 'the owner of this window — the continuation from the recorded position — reruns the round: the limit is already spent'
        resumeRunner().run(order, 'KILL-0', null)

        then: 'the park the dead run could not record is recorded now, on both replicas'
        def stop = thrown(RunParkedException)
        (stop.outcome() as TaskOutcome.Escalated).report() instanceof EscalationReport.AttemptsExhausted
        shape(cloneDir, 'KILL-0') == 'Parked'
        originTip('KILL-0') == localTip('KILL-0')
    }

    private GitModeRunner freshRunner(RunKillPoint point) {
        new GitModeRunner(newAssembly(new ByteArrayInputStream(new byte[0]), sink(), FakeAgentSupport.propertiesFor('plain-round')),
                RunKills.killedAt(point, TaskGitFixture.real()), registeredClone, LiveConsoleIO.onStdout())
    }

    private GitResumeRunner resumeRunner() {
        new GitResumeRunner(newAssembly(new ByteArrayInputStream(new byte[0]), sink(), FakeAgentSupport.propertiesFor('plain-round')),
                TaskGitFixture.real(), registeredClone, 'taskId')
    }

    private static PrintStream sink() {
        new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')
    }

    /** The branch shape's label, read through the production classifier over the repository's own ref. */
    private static String shape(Path repo, String taskId) {
        TaskGitFixture.real().branches().classifyShape(repo, taskId).label()
    }

    private TaskJsonDto tipTask(Path repo, String taskId) {
        TaskJsonMapper.readDto(UntrustedText.branchDocument(gitOutput(repo, 'show', "gnomish/${taskId}:${TASK_JSON}")))
    }

    private String localTip(String taskId) {
        gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}")
    }

    private String originTip(String taskId) {
        gitOutput(origin, 'rev-parse', "gnomish/${taskId}")
    }

    /** Origin holds a strict ancestor of the local tip: the same line, short of what the dead run committed. */
    private boolean originBehind(String taskId) {
        originTip(taskId) != localTip(taskId)
                && gitExitCode(cloneDir, 'merge-base', '--is-ancestor', originTip(taskId), localTip(taskId)) == 0
    }

    /** Everything a second recovery pass must leave untouched: both replicas' tips and the kept worktree. */
    private Map fingerprint(String taskId) {
        def worktree = registeredClone.worktrees().resolve(taskId)
        [
            local: localTip(taskId),
            origin: originTip(taskId),
            worktreeHead: gitOutput(worktree, 'rev-parse', 'HEAD'),
            worktreeDirty: gitOutput(worktree, 'status', '--porcelain'),
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
