package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, FR22, FR25 of add-sandbox-core (design D15, D16): in sandboxed mode the environment
 * channel — {@code putFile} into the box and its read-back — carries the round's state files
 * only. {@code task.json} is a factory-side bare-object commit: it never crosses into the box
 * and nothing reads it back from there. A box that records every file it is handed or asked for
 * is the observation.
 */
class TaskJsonChannelSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'CHAN-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final String TASK_JSON = '.gnomish-task/task.json'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    List<String> put = []
    List<String> read = []

    // FR25 of add-sandbox-core: the lifecycle writes of a sandboxed task are bare-object commits on
    //     the factory clone; the box only ever receives the state commit's files.
    // Scenario: git/git-task-persistence / task.json never crosses the environment channel
    def "FR25: a sandboxed task's task.json changes are committed factory-side and never pass through the box"() {
        given: 'a task created as bare objects in the factory clone, and a box materialized from its branch'
        Path cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        commit(cloneDir, 'seed.txt', 'seed')
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))
        def repository = new GitObjectsTaskRepository(gitObjects, new VirtualClock(Instant.parse('2026-01-01T00:00:00Z')),
                ClaimEpochSource.NONE, DenialCursorSource.NONE)
        repository.createTask(new TaskContext(TASK, UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box'))) {
                    @Override
                    void putFile(String path, byte[] content) {
                        put << path
                        super.putFile(path, content)
                    }

                    @Override
                    Optional<byte[]> readFile(String path, long sizeCap) {
                        read << path
                        super.readFile(path, sizeCap)
                    }
                }
        box.materialize(BRANCH, null)
        def taskJsonInBox = Files.readString(box.workingCopy.resolve(TASK_JSON))

        when: 'a round closes, then the task parks — a task.json change'
        def rounds = new CurrentRound()
        new EnvironmentRoundSnapshot(box, runner, cloneDir, OpenedRound.reopen(rounds, cloneDir, BRANCH))
                .snapshot(TASK, 'implement', 0)
        new EnvironmentAttemptPersistence(box, runner, cloneDir, gitObjects, rounds, ClaimEpochSource.NONE)
                .persist(TASK, TaskState.atStageStart('implement'), new ToolTrace(new AttemptKey(TASK, 'implement', 0), [
                    new ToolCall(0, 'bash', Instant.parse('2026-08-08T09:00:00Z'), Duration.ofMillis(100))
                ]))
        repository.recordOutcome(TASK, new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
                new EscalationReport.AttemptsExhausted(3)), TrackerWrite.OWED)

        then: 'the factory branch carries the changed task.json, committed factory-side'
        gitOutput(cloneDir, 'show', "refs/heads/${BRANCH}:${TASK_JSON}").contains('"escalated"')

        and: 'the channel carried the state files only — task.json was neither put nor read back'
        put.contains('.gnomish-task/state.json')
        !put.any { it.endsWith('task.json') }
        !read.any { it.endsWith('task.json') }

        and: 'the box still holds the task.json it was materialized with'
        Files.readString(box.workingCopy.resolve(TASK_JSON)) == taskJsonInBox
    }
}
