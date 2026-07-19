package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.UnsupportedStateFileVersionException
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.status.Outcome
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-O1 of add-git-workflow: reads {@code .gnomish-task/} files straight from the task
 * branch tip via {@code git show} — no worktree, no checkout, no local branch — and renders them
 * through the shared {@code StatusReport} pure function with live-only fields null.
 */
class BranchStateReaderSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def reader = new BranchStateReader(runner)
    Path cloneDir
    Path worktreesRoot

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private GitTaskRepository taskRepository() {
        new GitTaskRepository(runner, cloneDir, worktreesRoot)
    }

    private Path worktreeFor(String taskId) {
        worktreesRoot.resolve('clone').resolve(taskId)
    }

    private void persistRound(String taskId, TaskState state, String stage = 'implement', int round = 0) {
        def persistence = new GitAttemptPersistence(runner, worktreeFor(taskId), taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        persistence.persist(taskId, state, trace)
    }

    def "FR13: a task with only task.json (no rounds yet) reads back into a StatusReport, live fields null"() {
        given:
        def context = new TaskContext('PROJ-1', 'Fix the thing', 'Body text', [])
        taskRepository().createTask(context, null)
        persistRound('PROJ-1', TaskState.atStageStart('implement'))

        when:
        def result = reader.read(cloneDir, 'PROJ-1')

        then:
        result instanceof BranchStateResult.Found
        def report = (result as BranchStateResult.Found).report()
        report.taskId() == 'PROJ-1'
        report.title() == 'Fix the thing'
        report.currentStage() == 'implement'

        and: 'live-only activity and attemptLimit are null — this is a snapshot, not a live process (NFR-O1)'
        report.activity() == null
        report.attemptLimit() == null

        and: 'outcome is null — the visit is still in progress'
        report.outcome() == null
    }

    def "FR13: interrupted task (rounds present, task.json outcome null) renders as in-progress, matching nullable live fields of contract v1"() {
        given: 'a task that recorded a round but crashed before any recordOutcome call'
        taskRepository().createTask(new TaskContext('PROJ-2', 'T', 'B', []), null)
        persistRound('PROJ-2', TaskState.atStageStart('implement'))

        when:
        def result = reader.read(cloneDir, 'PROJ-2')

        then:
        def report = (result as BranchStateResult.Found).report()
        report.outcome() == null
        report.currentStage() == 'implement'
    }

    def "FR5: a task with a recorded terminal outcome surfaces it, unlike the interrupted case"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-3', 'T', 'B', []), null)
        persistRound('PROJ-3', TaskState.atStageStart('implement'))
        taskRepository().recordOutcome('PROJ-3', new TaskOutcome.Paused(TaskState.atStageStart('verify'), 'implement'))

        when:
        def result = reader.read(cloneDir, 'PROJ-3')

        then:
        def report = (result as BranchStateResult.Found).report()
        report.outcome() instanceof Outcome.Paused
        (report.outcome() as Outcome.Paused).passedStage() == 'implement'
    }

    def "FR5: an escalated task surfaces both outcome and lastEscalation from durable state"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-4', 'T', 'B', []), null)
        persistRound('PROJ-4', TaskState.atStageStart('implement'))
        def escalation = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        taskRepository().recordOutcome('PROJ-4', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), escalation))

        when:
        def result = reader.read(cloneDir, 'PROJ-4')

        then:
        def report = (result as BranchStateResult.Found).report()
        report.outcome() instanceof Outcome.Escalated
        (report.outcome() as Outcome.Escalated).report() == escalation
        report.lastEscalation() == escalation
    }

    def "FR13: decisions recorded on the branch round-trip into the report"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-5', 'T', 'B', []), null)
        persistRound('PROJ-5', TaskState.atStageStart('implement'))
        taskRepository().appendDecision('PROJ-5', new Decision('proceed', 'implement', 'operator', null))

        when:
        def result = reader.read(cloneDir, 'PROJ-5')

        then:
        def report = (result as BranchStateResult.Found).report()
        report.decisions().size() == 1
        report.lastDecision().body() == 'proceed'
    }

    def "FR13: a task branch reachable only via origin (narrow fetch) is read the same way, no local branch created"() {
        given: 'a bare origin carrying the task branch, and a fresh single-branch clone that lacks it'
        def bare = initBareRepo(tempDir, 'origin.git')
        def seedClone = initWorkingRepo(tempDir, 'seed-clone')
        new File(seedClone.toFile(), 'a.txt').text = 'first'
        runner.run(seedClone, 'add', 'a.txt')
        runner.run(seedClone, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(seedClone, 'remote', 'add', 'origin', bare.toString())
        runner.run(seedClone, 'push', 'origin', 'HEAD:refs/heads/main')

        def seedWorktrees = tempDir.resolve('seed-worktrees')
        new GitTaskRepository(runner, seedClone, seedWorktrees).createTask(new TaskContext('PROJ-6', 'T', 'B', []), null)
        new GitAttemptPersistence(runner, seedWorktrees.resolve('seed-clone').resolve('PROJ-6'), 'PROJ-6')
                .persist('PROJ-6', TaskState.atStageStart('implement'),
                new ToolTrace(new AttemptKey('PROJ-6', 'implement', 0), [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ]))
        runner.run(seedWorktrees.resolve('seed-clone').resolve('PROJ-6'), 'push', 'origin', 'gnomish/PROJ-6')

        def observerClone = tempDir.resolve('observer-clone')
        def cloneResult = runner.run(
                tempDir, 'clone', '--branch', 'main', '--single-branch', bare.toString(), observerClone.toString())
        assert cloneResult.exitCode() == 0: "clone failed: ${cloneResult.stderr()}"

        when:
        def result = reader.read(observerClone, 'PROJ-6')

        then:
        result instanceof BranchStateResult.Found
        (result as BranchStateResult.Found).report().taskId() == 'PROJ-6'

        and: 'no local branch was created — only the remote-tracking ref from the narrow fetch'
        runner.run(observerClone, 'rev-parse', '--verify', '--quiet', 'refs/heads/gnomish/PROJ-6').exitCode() != 0
        runner.run(observerClone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-6').exitCode() == 0

        and: 'the working copy is untouched — still on main, clean status'
        runner.run(observerClone, 'branch', '--show-current').stdout().trim() == 'main'
        runner.run(observerClone, 'status', '--porcelain').stdout().trim() == ''
    }

    def "FR13: branch absent everywhere is reported as not-found, not an exception"() {
        when:
        def result = reader.read(cloneDir, 'NO-SUCH-TASK')

        then:
        noExceptionThrown()
        result instanceof BranchStateResult.NotFound
    }

    def "FR4: unknown state.json version refuses the read with a clear error naming the file and version"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-7', 'T', 'B', []), null)
        persistRound('PROJ-7', TaskState.atStageStart('implement'))
        def worktree = worktreeFor('PROJ-7')
        def stateFile = new File(worktree.toFile(), '.gnomish-task/state.json')
        stateFile.text = stateFile.text.replaceFirst(/"version"\s*:\s*1/, '"version":2')
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'corrupt version')

        when:
        reader.read(cloneDir, 'PROJ-7')

        then:
        def ex = thrown(UnsupportedStateFileVersionException)
        ex.fileName() == 'state.json'
        ex.foundVersion() == 2
    }

    def "FR4: unknown task.json version refuses the read with a clear error naming the file and version"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-8', 'T', 'B', []), null)
        persistRound('PROJ-8', TaskState.atStageStart('implement'))
        def worktree = worktreeFor('PROJ-8')
        def taskFile = new File(worktree.toFile(), '.gnomish-task/task.json')
        taskFile.text = taskFile.text.replaceFirst(/"version"\s*:\s*1/, '"version":7')
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'corrupt version')

        when:
        reader.read(cloneDir, 'PROJ-8')

        then:
        def ex = thrown(UnsupportedStateFileVersionException)
        ex.fileName() == 'task.json'
        ex.foundVersion() == 7
    }

    def "read-only guarantee (M3): reading a remote-only task branch creates no local branch, no worktree, leaves the clone clean"() {
        given: 'a bare origin carrying the task branch, and an observer clone that never ran createTask itself'
        def bare = initBareRepo(tempDir, 'ro-origin.git')
        def seedClone = initWorkingRepo(tempDir, 'ro-seed-clone')
        new File(seedClone.toFile(), 'a.txt').text = 'first'
        runner.run(seedClone, 'add', 'a.txt')
        runner.run(seedClone, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(seedClone, 'remote', 'add', 'origin', bare.toString())
        runner.run(seedClone, 'push', 'origin', 'HEAD:refs/heads/main')

        def seedWorktrees = tempDir.resolve('ro-seed-worktrees')
        new GitTaskRepository(runner, seedClone, seedWorktrees).createTask(new TaskContext('PROJ-9', 'T', 'B', []), null)
        new GitAttemptPersistence(runner, seedWorktrees.resolve('ro-seed-clone').resolve('PROJ-9'), 'PROJ-9')
                .persist('PROJ-9', TaskState.atStageStart('implement'),
                new ToolTrace(new AttemptKey('PROJ-9', 'implement', 0), [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ]))
        runner.run(seedWorktrees.resolve('ro-seed-clone').resolve('PROJ-9'), 'push', 'origin', 'gnomish/PROJ-9')

        def observerClone = tempDir.resolve('ro-observer-clone')
        def cloneResult = runner.run(
                tempDir, 'clone', '--branch', 'main', '--single-branch', bare.toString(), observerClone.toString())
        assert cloneResult.exitCode() == 0: "clone failed: ${cloneResult.stderr()}"
        def observerWorktrees = tempDir.resolve('ro-observer-worktrees')
        def readerUnderTest = new BranchStateReader(runner)

        when:
        def result = readerUnderTest.read(observerClone, 'PROJ-9')

        then:
        result instanceof BranchStateResult.Found

        and: 'no local branch was created in the observer clone — only the remote-tracking ref from the narrow fetch'
        runner.run(observerClone, 'rev-parse', '--verify', '--quiet', 'refs/heads/gnomish/PROJ-9').exitCode() != 0

        and: 'the observer clone stayed on main with a clean working copy — no checkout happened'
        runner.run(observerClone, 'branch', '--show-current').stdout().trim() == 'main'
        runner.run(observerClone, 'status', '--porcelain').stdout().trim() == ''

        and: 'no worktree was materialized anywhere the reader could have created one'
        !observerWorktrees.toFile().exists()
    }
}
