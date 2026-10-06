package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR10 of make-run-headless (design D8): the host terminal boundaries of {@code gnomish
 * run} — the fresh run and the resume — record a stop as a park on the task branch before the
 * process exits: {@code task.json} on the tip carries the outcome ({@code lastEscalation} with an
 * escalation), no pending marker is set (no tracker write follows), exactly one lifecycle commit
 * follows the last round commit, no cleanup commit was made, the worktree is kept, origin carries the park, and the exit code is 10 or 11.
 *
 * <p>The container boundaries are pinned by {@code RunParkRecordingContainerE2ESpec}, which needs a
 * Docker daemon.
 */
class RunParkRecordingSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

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

    private GitModeRunner freshRunner(boolean killedBeforePark = false) {
        def git = killedBeforePark ? RunKills.killedBeforeParkRecord(TaskGitFixture.real()) : TaskGitFixture.real()
        new GitModeRunner(newAssembly(new ByteArrayInputStream(new byte[0]), sink(), FakeAgentSupport.propertiesFor('plain-round')),
                git, registeredClone, LiveConsoleIO.onStdout())
    }

    private GitResumeRunner resumeRunner() {
        new GitResumeRunner(newAssembly(new ByteArrayInputStream(new byte[0]), sink(), FakeAgentSupport.propertiesFor('plain-round')),
                TaskGitFixture.real(), registeredClone, 'taskId')
    }

    private static PrintStream sink() {
        new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')
    }

    private TaskJsonDto tipTask(String taskId) {
        TaskJsonMapper.readDto(UntrustedText.branchDocument(gitOutput(cloneDir, 'show', "gnomish/${taskId}:${TASK_JSON}")))
    }

    /** What every recorded park leaves behind, whichever boundary recorded it. */
    private void assertParked(String taskId, RunParkedException stop, Class<? extends TaskOutcomeDto> outcome, int exitCode) {
        def branch = "gnomish/${taskId}"
        def task = tipTask(taskId)
        assert outcome.isInstance(task.outcome())
        assert (task.lastEscalation() != null) == (outcome == TaskOutcomeDto.Escalated)
        // Design D8 (revised 2026-10-06): a manual run owes no tracker write, so the record carries no
        // marker at all — the key is absent, never set and then cleared.
        assert task.trackerWritePending() == null
        // Exactly one lifecycle commit follows the engine's last round commit: the park's outcome
        // commit, with no receipt commit behind it.
        def subjects = gitOutput(cloneDir, 'log', '--format=%s', branch).readLines()
        assert subjects.takeWhile {
            it.startsWith('gnomish: task ')
        }.size() == 1
        assert !subjects.contains('gnomish: task write-confirmed')
        assert Files.exists(registeredClone.worktrees().resolve(taskId))
        assert gitOutput(origin, 'rev-parse', branch) == gitOutput(cloneDir, 'rev-parse', branch)
        assert new RunExitCodeMapper().getExitCode(stop) == exitCode
    }

    def "FR1, FR10: a fresh host run that escalates records the park and exits 10"() {
        when:
        freshRunner().run(new RunOrder(cloneDir, null, ParkPipelines.escalating(), false), context('PARK-1'), TaskState.atStageStart('build'))

        then:
        def stop = thrown(RunParkedException)
        assertParked('PARK-1', stop, TaskOutcomeDto.Escalated, 10)
    }

    def "FR2, FR10: a fresh host run that reaches a checkpoint records the park and exits 11"() {
        when:
        freshRunner().run(new RunOrder(cloneDir, null, ParkPipelines.pausing(), false), context('PARK-2'), TaskState.atStageStart('build'))

        then:
        def stop = thrown(RunParkedException)
        assertParked('PARK-2', stop, TaskOutcomeDto.Paused, 11)
    }

    def "FR1, FR10: a host resume that escalates records the park and exits 10"() {
        given: 'a run killed at its escalation before the park was recorded — the interrupted-run shape'
        def order = new RunOrder(cloneDir, null, ParkPipelines.escalating(), false)
        when:
        freshRunner(true).run(order, context('PARK-3'), TaskState.atStageStart('build'))
        then:
        thrown(RunKills.SimulatedKill)
        tipTask('PARK-3').outcome() == null

        when: 'the resume reproduces the escalation — the attempt limit is already spent'
        resumeRunner().run(order, 'PARK-3', null)

        then:
        def stop = thrown(RunParkedException)
        assertParked('PARK-3', stop, TaskOutcomeDto.Escalated, 10)
    }

    def "FR2, FR10: a host resume that reaches a checkpoint records the park and exits 11"() {
        given: 'a run killed at its first checkpoint before the park was recorded'
        def order = new RunOrder(cloneDir, null, ParkPipelines.pausing(), false)
        when:
        freshRunner(true).run(order, context('PARK-4'), TaskState.atStageStart('build'))
        then:
        thrown(RunKills.SimulatedKill)

        when: 'the resume continues from the recorded position and stops at the second checkpoint'
        resumeRunner().run(order, 'PARK-4', null)

        then:
        def stop = thrown(RunParkedException)
        (stop.outcome() as TaskOutcome.Paused).passedStage() == 'deploy'
        assertParked('PARK-4', stop, TaskOutcomeDto.Paused, 11)
    }
}
