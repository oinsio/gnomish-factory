package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D18), the host route of F135: the round close runs
 * the one range inspection over {@code previousTip..HEAD} and restores the factory-owned paths from
 * the registered commit before {@code git add -A}, so an envelope the gnome edited — left in the
 * working tree, or committed above the register — never rides a factory commit; a violation's
 * {@code Aborted} outcome is built from the registered envelope, never the tip's.
 */
class HostRoundEnvelopeSpec extends Specification implements RegisteredRoundFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupRegisteredRound()
    }

    private String fileAt(String rev, String path) {
        gitOutput(cloneDir, 'show', "${rev}:${path}")
    }

    /** The round's persistence, built as the run builds it: before the gnome works. */
    private def persistence() {
        store.attemptPersistence(worktree(), TASK)
    }

    private static void persistRound(def persistence, int attempt) {
        persistence.persist(TASK, TaskState.atStageStart('implement'), new ToolTrace(new AttemptKey(TASK, 'implement', attempt), []))
    }

    // Scenario: git/git-task-persistence / An uncommitted envelope edit does not ride the host round commit
    def "FR13: an uncommitted envelope edit does not ride the round commit"() {
        given: 'the gnome edits task.json in the working tree and leaves it uncommitted'
        def registered = registeredId(cloneDir, TASK)
        def persistence = persistence()
        def path = worktree().resolve('.gnomish-task/task.json')
        def factoryTaskJson = Files.readString(path)
        Files.writeString(path, '{"forged":true}')

        when:
        persistRound(persistence, 1)

        then: 'the edit is reported as a violation'
        def violation = thrown(RoundBoundaryViolationException)
        violation.message.contains('.gnomish-task/task.json')

        and: 'no round commit carries it: the branch and the register did not move, and the worktree is the factory\'s again'
        gitOutput(cloneDir, 'rev-parse', TIP_REF) == registered
        registeredId(cloneDir, TASK) == registered
        Files.readString(path) == factoryTaskJson
    }

    // Scenario: git/git-task-persistence / A host round boundary checks the worktree
    def "FR13: a clean worktree closes the round, its commit carrying the factory's own envelope"() {
        given:
        def registered = registeredId(cloneDir, TASK)
        def persistence = persistence()
        commit(worktree(), 'App.java', 'class App {}')

        when:
        persistRound(persistence, 1)

        then: 'branch and register name the round commit together, above the gnome commit'
        def tip = gitOutput(cloneDir, 'rev-parse', TIP_REF)
        registeredId(cloneDir, TASK) == tip
        tip != registered
        fileAt(tip, '.gnomish-task/task.json') == fileAt(registered, '.gnomish-task/task.json')
    }

    // Scenario: git/git-task-persistence / A violation's Aborted outcome is built from the registered envelope
    def "FR13: a violation's Aborted outcome is built from the registered envelope"() {
        given: 'the gnome commits a forged envelope above the register'
        def registered = registeredId(cloneDir, TASK)
        def persistence = persistence()
        gnomeRewrites('state.json') { it.forged = true }
        gnomeRewrites('task.json') { it.forged = true }

        when: 'the round close refuses the range'
        persistRound(persistence, 1)

        then:
        thrown(RoundBoundaryViolationException)

        when: 'the run aborts'
        store.taskRepository(registeredClone).recordOutcome(
                TASK, new TaskOutcome.Aborted(TaskState.atStageStart('implement'), new AttemptKey(TASK, 'implement', 1), UntrustedText.factory('boundary violation')), TrackerWrite.OWED)

        then: 'the Aborted commit sits on the evidence, with the register\'s envelope plus the outcome'
        def tip = gitOutput(cloneDir, 'rev-parse', TIP_REF)
        registeredId(cloneDir, TASK) == tip
        fileAt(tip, '.gnomish-task/state.json') == fileAt(registered, '.gnomish-task/state.json')
        !fileAt(tip, '.gnomish-task/task.json').contains('forged')
        store.readTaskRecord(worktree(), TASK).get().outcome().getClass().simpleName == 'Aborted'
        gitOutput(cloneDir, 'log', '--format=%s', "${registered}..${tip}").readLines().size() == 3
    }

    def "FR13: the round commit lands with its register, and HEAD, index and worktree follow it"() {
        when:
        persistRound(persistence(), 1)

        then:
        def tip = gitOutput(cloneDir, 'rev-parse', TIP_REF)
        registeredId(cloneDir, TASK) == tip
        gitOutput(worktree(), 'rev-parse', 'HEAD') == tip
        gitOutput(worktree(), 'status', '--porcelain') == ''
    }

    def "FR13: the round is refused as a violation when HEAD left the task branch"() {
        given:
        def persistence = persistence()
        assert gitExitCode(worktree(), 'checkout', '-q', '-b', 'elsewhere') == 0

        when:
        persistRound(persistence, 1)

        then:
        def violation = thrown(RoundBoundaryViolationException)
        violation.message.contains('HEAD is not on the task branch')
    }

    def "FR13: a restore that fails fails the round as infrastructure"() {
        given:
        def persistence = new GitAttemptPersistence(
                new ScriptedRestoreFailure(runner), worktree(), TASK, ClaimEpochSource.NONE)

        when:
        persistence.persist(TASK, TaskState.atStageStart('implement'), new ToolTrace(new AttemptKey(TASK, 'implement', 1), []))

        then:
        def failure = thrown(GitPersistFailedException)
        failure.message.contains('restore of the factory-owned paths')
    }

    def "FR13: a lifecycle write whose restore fails refuses, rather than staging the gnome's envelope"() {
        given:
        def repository = new GitTaskRepository(new ScriptedRestoreFailure(runner), registeredClone, ClaimEpochSource.NONE, new VirtualClock())
        def registered = registeredId(cloneDir, TASK)

        when:
        repository.recordOutcome(TASK, new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'), TrackerWrite.OWED)

        then:
        def failure = thrown(com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException)
        failure.message.contains('restoring the factory-owned paths')
        gitOutput(cloneDir, 'rev-parse', TIP_REF) == registered
    }

    /** Real git, with every {@code git restore} refused. */
    static final class ScriptedRestoreFailure implements GitRunner {

        private final GitRunner real

        ScriptedRestoreFailure(GitRunner real) {
            this.real = real
        }

        @Override
        GitCommandResult run(Path cwd, String... args) {
            args[0] == 'restore' ? GitCommandResult.of(1, '', 'fatal: cannot restore\n') : real.run(cwd, args)
        }

        @Override
        GitCommandResult run(Path cwd, com.github.oinsio.gnomish.gittransfer.GitTransfer transfer) {
            real.run(cwd, transfer)
        }

        @Override
        GitCommandResult run(Path cwd, RefTransaction transaction) {
            real.run(cwd, transaction)
        }
    }
}
