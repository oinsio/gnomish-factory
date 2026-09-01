package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.SeededCloneFixture
import com.github.oinsio.gnomish.app.port.git.TaskListingFailedException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, FR6 of add-git-workflow (task 5.3): {@code gnomish status --dir <clone> <task>} renders
 * text/JSON v1 from the branch state reader and shows the worktree path. Real git repos throughout
 * (matching {@code BranchStateReaderSpec}'s adapter-layer convention) — no stubbing of the reader.
 * The seeded-clone setup comes from {@link SeededCloneFixture} (test-fixtures, shared with the
 * git adapter's own usage-walker specs).
 */
class StatusCommandSpec extends Specification implements SeededCloneFixture, StdoutCaptureFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupSeededClone()
    }

    private StatusCommand newCommand() {
        new StatusCommand(TaskGitFixture.real(), worktreesRoot)
    }

    private void persistRound(String taskId, TaskState state, String stage = 'implement', int round = 0) {
        new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE).createTask(new TaskContext(taskId, 'Fix the thing', 'Body', []), null, TaskState.atStageStart('implement'))
        def worktree = worktreesRoot.resolve('clone').resolve(taskId)
        def persistence = new GitAttemptPersistence(runner, worktree, taskId, ClaimEpochSource.NONE)
        def trace = new ToolTrace(new AttemptKey(taskId, stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        persistence.persist(taskId, state, trace)
    }

    def "FR13: text render of a found task prints the status block"() {
        given:
        persistRound('PROJ-1', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-1')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('Task: PROJ-1')
        output.contains('Fix the thing')
        output.contains('Stage: implement')
    }

    def "FR13: --json render of a found task prints the v1 JSON contract"() {
        given:
        persistRound('PROJ-2', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-2', '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('"version" : 1')
        output.contains('"id" : "PROJ-2"')
    }

    def "FR6: the worktree path is shown for a found task"() {
        given:
        persistRound('PROJ-3', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-3')
        def expectedWorktree = worktreesRoot.resolve('clone').resolve('PROJ-3')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('Worktree: ' + expectedWorktree)
    }

    def "task-inspection: interrupted task (outcome null) is rendered honestly as in-progress"() {
        given: 'a task with a recorded round but no recordOutcome call — a crash mid-flight'
        persistRound('PROJ-4', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-4', '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then: 'contract v1 renders a null outcome field, matching the nullable live fields'
        output.contains('"outcome" : null')
    }

    def "FR13, UX3: a task branch absent everywhere prints a plain not-found message and throws TaskNotFoundException, no stack trace"() {
        given:
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'NO-SUCH-TASK')

        when:
        def output = captureStdoutExpectingThrow(TaskNotFoundException) {
            newCommand().run(args)
        }

        then:
        output.contains('task not found')
        output.contains('NO-SUCH-TASK')
    }

    def "FR13, UX3, D15: 'Deleted branch after merge' — a branch that existed and was deleted reports not-found the same as a never-existing task"() {
        given: 'a task branch created, its worktree removed, then the branch deleted — mirroring a merged-and-cleaned-up PR'
        persistRound('PROJ-7', TaskState.atStageStart('implement'))
        def worktree = worktreesRoot.resolve('clone').resolve('PROJ-7')
        runner.run(cloneDir, 'worktree', 'remove', '--force', worktree.toString())
        runner.run(cloneDir, 'branch', '-D', 'gnomish/PROJ-7')
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-7')

        when:
        def output = captureStdoutExpectingThrow(TaskNotFoundException) {
            newCommand().run(args)
        }

        then:
        output.contains('task not found: PROJ-7')
    }

    def "FR13: status without a <task> argument lists all tasks as a text table"() {
        given:
        persistRound('PROJ-5', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir)

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('PROJ-5')
        output.contains('implement')
    }

    def "FR13: status without a <task> argument, --json lists all tasks as a JSON array"() {
        given:
        persistRound('PROJ-6', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('"taskId" : "PROJ-6"')
    }

    def "FR13: status without a <task> argument prints 'no tasks found' when the clone has no gnomish/* branch"() {
        given:
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir)

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        noExceptionThrown()
        output.contains('no tasks found')
    }

    // FR13 of harden-logging-observability, "A failed enumeration is an error, not an empty table":
    // per-branch degradation stops at the branch — the listing itself failing is the command
    // failing, because "verified: no tasks" and "could not look" are opposite answers.
    def "FR13: a failed enumeration fails the command instead of printing an empty table"() {
        given: 'a --dir that is not a git repository, so the ref enumeration exits non-zero'
        def notARepo = Files.createDirectory(tempDir.resolve('not-a-repo'))
        def args = new DefaultApplicationArguments('status', '--dir=' + notARepo)

        when:
        def printed = captureStdout { newCommand().run(args) }

        then: 'the command fails naming the git failure, and prints no table at all'
        def failure = thrown(TaskListingFailedException)
        failure.message.contains('could not enumerate')
        printed == null
    }

    def "UsageException: --dir is required"() {
        given:
        def args = new DefaultApplicationArguments('status', 'PROJ-1')

        when:
        newCommand().run(args)

        then:
        thrown(UsageException)
    }

    // FR16, UX4 of harden-task-branch-contract: every legal shape renders calmly, and only the
    // three quarantine shapes refuse inspection — with a diagnosis, never a stack trace.
    def "FR16: a delivered branch renders as delivered, not as a missing state file"() {
        given: 'a completed task whose cleanup commit stripped .gnomish-task/ from the tip'
        persistRound('DELIVERED-1', TaskState.atStageStart('implement'))
        def repository = new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
        repository.recordOutcome('DELIVERED-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('DELIVERED-1')
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'DELIVERED-1')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        noExceptionThrown()
        output.contains('Task: DELIVERED-1')
        output.contains('Shape: Delivered')
        !output.contains('Diagnosis')
    }

    def "FR16: an unknown state-file version refuses inspection with a diagnosis, no stack trace, nothing mutated"() {
        given: 'a task branch whose state.json declares version 2'
        persistRound('VERSION-2', TaskState.atStageStart('implement'))
        def worktree = worktreesRoot.resolve('clone').resolve('VERSION-2')
        def stateFile = new File(worktree.toFile(), '.gnomish-task/state.json')
        stateFile.text = stateFile.text.replaceFirst(/"version"\s*:\s*1/, '"version":2')
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'version 2')
        def tipBefore = runner.run(cloneDir, 'rev-parse', 'gnomish/VERSION-2').stdout().trim()
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'VERSION-2')

        when:
        def output = captureStdoutExpectingThrow(BranchShapeRefusedException) {
            newCommand().run(args)
        }

        then: 'the diagnosis names the file, the observed version and the supported one'
        output.contains('Shape: UnsupportedVersion')
        output.contains('state.json declaring version 2 where this factory supports 1')

        and: 'nothing was mutated — the branch tip is where it was'
        runner.run(cloneDir, 'rev-parse', 'gnomish/VERSION-2').stdout().trim() == tipBefore
    }

    def "FR16: --json renders the refusing shape as JSON with its diagnosis"() {
        given:
        persistRound('VERSION-3', TaskState.atStageStart('implement'))
        def worktree = worktreesRoot.resolve('clone').resolve('VERSION-3')
        def stateFile = new File(worktree.toFile(), '.gnomish-task/state.json')
        stateFile.text = stateFile.text.replaceFirst(/"version"\s*:\s*1/, '"version":9')
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'version 9')
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'VERSION-3', '--json')

        when:
        def output = captureStdoutExpectingThrow(BranchShapeRefusedException) {
            newCommand().run(args)
        }

        then:
        output.contains('"shape" : "UnsupportedVersion"')
        output.contains('"taskId" : "VERSION-3"')
        output.contains('declaring version 9')
    }

    def "FR16, UX4: a mixed-shape clone lists one row per task, the bad branch included"() {
        given: 'one healthy task, one delivered task and one with a broken state file'
        persistRound('MIXED-OK', TaskState.atStageStart('implement'))
        persistRound('MIXED-DONE', TaskState.atStageStart('implement'))
        def repository = new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
        repository.recordOutcome('MIXED-DONE', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('MIXED-DONE')
        persistRound('MIXED-BAD', TaskState.atStageStart('implement'))
        def broken = worktreesRoot.resolve('clone').resolve('MIXED-BAD')
        new File(broken.toFile(), '.gnomish-task/state.json').text = '{ not json'
        runner.run(broken, 'add', '-A')
        runner.run(broken, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'break')
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir)

        when:
        def output = captureStdout { newCommand().run(args) }

        then: 'the listing survives the bad branch and names every shape'
        noExceptionThrown()
        output.contains('MIXED-OK')
        output.contains('MIXED-DONE')
        output.contains('Delivered')
        output.contains('MIXED-BAD')
        output.contains('Corrupt')
    }
}
