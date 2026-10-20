package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
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
    private static final String REGISTER = 'refs/gnomish-factory/PROJ-1'

    private final GitProcessRunner runner = new GitProcessRunner()
    private Path origin
    private Path cloneDir

    def setup() {
        origin = initBareRepo(tempDir, 'origin.git')
        cloneDir = tempDir.resolve('clone')
        seedClone(tempDir, origin.toString(), cloneDir, '-q')
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir, 'init')
        runner.run(cloneDir, 'push', '-q', 'origin', 'HEAD:refs/heads/main')
    }

    private TaskRepository undecoratedHostRepository() {
        new GitTaskRepository(
                runner, RegisteredCloneFixture.resolvedOrRegistered(tempDir.resolve('home'), cloneDir), ClaimEpochSource.NONE, new VirtualClock())
    }

    private String localTip() {
        gitOutput(cloneDir, 'rev-parse', "refs/heads/${BRANCH}")
    }

    private Optional<String> originTip() {
        new RemoteBranchTip(runner).read(cloneDir, TASK_ID)
    }

    /**
     * The crash shape: a task recorded through the STRICT repository — no lifecycle decorator, so
     * no push ever ran — which is exactly the state an instance killed between the commit and its
     * push leaves behind on disk. STARTED was registered, as its load-bearing first push registers
     * it before anything else; the park commit was not (killed between the commit and the closing
     * push's register write, FR13 of tighten-spec-contract).
     *
     * @return the registered STARTED commit
     */
    private String driveWithoutPushing() {
        def repository = undecoratedHostRepository()
        repository.createTask(new TaskContext(TASK_ID, UntrustedText.tracker('Fix it'), UntrustedText.tracker('Body'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def started = registerTip()
        repository.recordOutcome(TASK_ID, new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'), TrackerWrite.OWED)
        started
    }

    /** The clone's register moved to the branch tip, as a closing push's register write does. */
    private String registerTip() {
        gitOutput(cloneDir, 'update-ref', REGISTER, "refs/heads/${BRANCH}")
        gitOutput(cloneDir, 'rev-parse', REGISTER)
    }

    /** Migrated to the shared helper (`.claude/rules/logging.md`) during the duplication sweep. */
    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        LogCaptureSupport.capture(OriginReconciliation, Level.DEBUG, emit)
    }

    // Scenario: git/git-task-persistence / A missed terminal push is healed on resume
    def "a terminal commit that never reached origin is delivered by the next touchpoint"() {
        given: 'an instance crashed after committing the park, before its push landed'
        driveWithoutPushing()
        assert originTip() == Optional.empty()

        when: 'any instance later touches the task'
        new GitTaskBranches(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure()).reconcileRemote(cloneDir, TASK_ID, 'resume-start')

        then: 'origin now carries the park commit — healed without the crashed instance coming back'
        originTip() == Optional.of(localTip())

        and: 'the register travelled with it: the park landed with its register in one transaction (FR13 of tighten-spec-contract)'
        gitOutput(origin, 'rev-parse', REGISTER) == localTip()
    }

    def "a partially delivered branch is caught up to its local tip"() {
        given: 'the creation commit reached origin, the terminal one did not'
        def repository = undecoratedHostRepository()
        repository.createTask(new TaskContext(TASK_ID, UntrustedText.tracker('Fix it'), UntrustedText.tracker('Body'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        registerTip()
        assert new ClosingPush(runner).push(cloneDir, TASK_ID).exitCode() == 0
        def deliveredTip = originTip()
        repository.recordOutcome(TASK_ID, new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'), TrackerWrite.OWED)
        def parked = registerTip()
        assert originTip() == deliveredTip

        when:
        new GitTaskBranches(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure()).reconcileRemote(cloneDir, TASK_ID, 'terminal-boundary')

        then: 'branch and register caught up together'
        originTip() == Optional.of(localTip())
        gitOutput(origin, 'rev-parse', REGISTER) == parked
    }

    // Scenario: git/git-task-persistence / Reconciliation never blocks the run
    def "an unreachable origin degrades to a WARN and blocks nothing"() {
        given:
        driveWithoutPushing()
        FixtureGitConfig.setRemoteUrl(cloneDir, 'origin', tempDir.resolve('nowhere.git').toString())

        when:
        def events = capture {
            new GitTaskBranches(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure()).reconcileRemote(cloneDir, TASK_ID, 'resume-start')
        }

        then:
        noExceptionThrown()
        events.findAll { it.level == Level.WARN }.size() == 1
    }

    def "a task with no local branch is nothing to reconcile"() {
        when:
        def events = capture {
            new GitTaskBranches(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure()).reconcileRemote(cloneDir, 'NO-SUCH', 'resume-start')
        }

        then:
        noExceptionThrown()
        events.isEmpty()
        new RemoteBranchTip(runner).read(cloneDir, 'NO-SUCH') == Optional.empty()
    }
}
