package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * M1, UX1, UX3 of fix-lifecycle-push, against a real bare-repo origin: a task driven to
 * {@code Completed} through the production wiring of either mode — host ({@code GitTaskStore} over
 * a worktree) or sandboxed ({@code GitObjectsTaskRepository} over bare objects) — ends with the
 * origin tip equal to the local branch tip, cleanup at the tip, and not one manual push anywhere in
 * the drive. A run in a clone with no origin at all stays entirely silent.
 */
class LifecyclePushIntegrationSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private static final String TASK_ID = 'PROJ-1'
    private static final String BRANCH = 'gnomish/PROJ-1'

    private final GitProcessRunner runner = new GitProcessRunner()
    private Path origin
    private Path cloneDir

    def setup() {
        origin = initBareRepo(tempDir, 'origin.git')
        cloneDir = tempDir.resolve('clone')
        runner.run(tempDir, 'clone', '-q', origin.toString(), cloneDir.toString())
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir, 'init')
        runner.run(cloneDir, 'push', '-q', 'origin', 'HEAD:refs/heads/main')
    }

    private String localTip() {
        gitOutput(cloneDir, 'rev-parse', "refs/heads/${BRANCH}")
    }

    private Optional<String> originTip() {
        new RemoteBranchTip(runner).read(cloneDir, BRANCH)
    }

    /**
     * The production wiring of {@code mode} over {@code clone}: host is whatever {@code
     * GitTaskStore} hands a run out, sandbox is bare-object writes plus the same lifecycle
     * decorator {@code ContainerRunSupport} wraps them in.
     */
    private TaskRepository repositoryFor(String mode, Path clone) {
        if (mode == 'host') {
            return new GitTaskStore(runner).taskRepository(clone, tempDir.resolve("worktrees-${clone.fileName}"))
        }
        Path indexDir = tempDir.resolve("index-${clone.fileName}")
        Files.createDirectories(indexDir)
        def bare = new GitObjectsTaskRepository(GitObjects.open(clone.resolve('.git'), indexDir))
        new PushBestEffortTaskRepository(bare, runner, clone)
    }

    def "M1: a task driven to Completed in #mode mode leaves origin at the local tip, with no manual push"() {
        given:
        def repository = repositoryFor(mode, cloneDir)

        when: 'the whole lifecycle runs — creation, then the terminal outcome and its cleanup commit'
        repository.createTask(new TaskContext(TASK_ID, 'Fix it', 'Body', []), 'HEAD')
        def tipAfterStart = originTip()
        repository.recordOutcome(TASK_ID, new TaskOutcome.Completed(TaskState.atStageStart('implement')))

        then: 'the creation commit was already on origin before the task ever ended'
        tipAfterStart.isPresent()

        and: 'origin now holds exactly the local tip'
        originTip() == Optional.of(localTip())

        and: 'UX1: that tip is the cleanup commit — the PR diff carries no .gnomish-task files'
        gitOutput(cloneDir, 'log', '-1', '--format=%s', "refs/heads/${BRANCH}") == ServiceCommitMessages.cleanup()
        gitOutput(cloneDir, 'ls-tree', '--name-only', "refs/heads/${BRANCH}") == 'a.txt'

        where:
        mode << ['host', 'sandbox']
    }

    def "UX3: a #mode run in a clone with no origin attempts no push and logs nothing"() {
        given: 'a clone with no origin remote at all'
        def local = initWorkingRepo(tempDir, 'local')
        Files.writeString(local.resolve('a.txt'), 'first')
        commitAll(local, 'init')
        def repository = repositoryFor(mode, local)

        when:
        List<ILoggingEvent> events = capture {
            repository.createTask(new TaskContext(TASK_ID, 'Fix it', 'Body', []), 'HEAD')
            repository.recordOutcome(TASK_ID, new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        }

        then:
        noExceptionThrown()
        events.isEmpty()

        and: 'the work is still durably recorded locally — durability never depended on the push'
        gitOutput(local, 'log', '-1', '--format=%s', "refs/heads/${BRANCH}") == ServiceCommitMessages.cleanup()

        where:
        mode << ['host', 'sandbox']
    }

    /** The {@code LifecyclePush} log events emitted while {@code emit} runs. */
    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(LifecyclePush)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        appender.list
    }
}
