package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, M1 of fix-lifecycle-push, end to end over a real bare-repo origin: the crash-shaped fixture
 * — a terminal outcome committed locally while its push never landed — is healed by the very next
 * touchpoint any instance runs, through the same {@code TaskBranchGit.reconcileRemote} port method
 * the resume bootstraps and the terminal boundary call. An unreachable origin degrades to a WARN
 * and blocks nothing.
 */
class TaskBranchReconciliationSpec extends Specification implements BareGitRepoFixture {

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

    private TaskRepository undecoratedHostRepository() {
        new GitTaskRepository(runner, cloneDir, tempDir.resolve('worktrees'), ClaimEpochSource.NONE)
    }

    private String localTip() {
        gitOutput(cloneDir, 'rev-parse', "refs/heads/${BRANCH}")
    }

    private Optional<String> originTip() {
        new RemoteBranchTip(runner).read(cloneDir, BRANCH)
    }

    /**
     * The crash shape: a task recorded through the STRICT repository — no lifecycle decorator, so
     * no push ever ran — which is exactly the state an instance killed between the commit and its
     * push leaves behind on disk.
     */
    private void driveWithoutPushing() {
        def repository = undecoratedHostRepository()
        repository.createTask(new TaskContext(TASK_ID, 'Fix it', 'Body', []), 'HEAD', TaskState.atStageStart('implement'))
        repository.recordOutcome(TASK_ID, new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))
    }

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(OriginReconciliation)
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

    def "a terminal commit that never reached origin is delivered by the next touchpoint"() {
        given: 'an instance crashed after committing the park, before its push landed'
        driveWithoutPushing()
        assert originTip() == Optional.empty()

        when: 'any instance later touches the task'
        new GitTaskBranches(runner).reconcileRemote(cloneDir, TASK_ID, 'resume-start')

        then: 'origin now carries the park commit — healed without the crashed instance coming back'
        originTip() == Optional.of(localTip())
    }

    def "a partially delivered branch is caught up to its local tip"() {
        given: 'the creation commit reached origin, the terminal one did not'
        def repository = undecoratedHostRepository()
        repository.createTask(new TaskContext(TASK_ID, 'Fix it', 'Body', []), 'HEAD', TaskState.atStageStart('implement'))
        assert new RefspecPush(runner).push(cloneDir, BRANCH).exitCode() == 0
        def deliveredTip = originTip()
        repository.recordOutcome(TASK_ID, new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))
        assert originTip() == deliveredTip

        when:
        new GitTaskBranches(runner).reconcileRemote(cloneDir, TASK_ID, 'terminal-boundary')

        then:
        originTip() == Optional.of(localTip())
    }

    def "an unreachable origin degrades to a WARN and blocks nothing"() {
        given:
        driveWithoutPushing()
        gitOutput(cloneDir, 'remote', 'set-url', 'origin', tempDir.resolve('nowhere.git').toString())

        when:
        def events = capture {
            new GitTaskBranches(runner).reconcileRemote(cloneDir, TASK_ID, 'resume-start')
        }

        then:
        noExceptionThrown()
        events.findAll { it.level == Level.WARN }.size() == 1
    }

    def "a task with no local branch is nothing to reconcile"() {
        when:
        def events = capture {
            new GitTaskBranches(runner).reconcileRemote(cloneDir, 'NO-SUCH', 'resume-start')
        }

        then:
        noExceptionThrown()
        events.isEmpty()
        new RemoteBranchTip(runner).read(cloneDir, 'gnomish/NO-SUCH') == Optional.empty()
    }
}
