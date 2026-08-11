package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, FR22 of add-sandbox-core (design D15): the sandboxed persist port is
 * strict — when the in-box state commit itself fails, the failure surfaces as
 * {@link GitPersistFailedException} carrying the captured in-box git output as
 * diagnostic detail, never a silent return and never an empty message.
 */
class EnvironmentStateCommitFailureSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'FAIL-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    LocalBoxEnvironment box
    AttemptCommitRef attemptRef = new AttemptCommitRef()
    EnvironmentRoundSnapshot snapshotStep
    EnvironmentAttemptPersistence persistence

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize(BRANCH, null)
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))
        snapshotStep = new EnvironmentRoundSnapshot(box, runner, cloneDir, TASK, attemptRef)
        persistence = new EnvironmentAttemptPersistence(box, runner, cloneDir, gitObjects, TASK, attemptRef)
    }

    // FR21, FR22: the exception's detail is the in-box command output read back through the
    // exec handle — a locked index makes git name the lock file, and that name must survive
    // into the persist failure for diagnosis
    def "FR21: a failed in-box state commit surfaces the captured git output in the persist failure"() {
        given: 'a snapshotted round'
        new File(box.workingCopy.toFile(), 'work.txt').text = 'gnome work'
        snapshotStep.snapshot(TASK, 'implement', 1)

        and: 'a locked in-box index, so the state-commit git add must fail loudly'
        new File(box.workingCopy.toFile(), '.git/index.lock').text = ''

        when:
        persistence.persist(TASK, TaskState.atStageStart('implement'), new ToolTrace(
                new AttemptKey(TASK, 'implement', 1),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-08-08T09:00:00Z'), Duration.ofMillis(100))
                ]))

        then: 'the strict port throws, naming the step and carrying the in-box git output'
        def ex = thrown(GitPersistFailedException)
        ex.message.contains('in-box state commit')
        ex.message.contains('index.lock')
    }
}
