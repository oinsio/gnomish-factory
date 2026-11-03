package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, NFR-C1 of add-git-workflow: {@link UsageHistoryWalker} edge cases — service commits
 * (salvage/cleanup), no-op re-commits, remote-only branches, and a defensive blank-line filter. Core round-detection scenarios live in
 * {@link UsageHistoryWalkerSpec}; split out to keep both files under the 200-line cap
 * (.claude/rules/process-invariants.md).
 */
class UsageHistoryWalkerEdgeCasesSpec extends Specification
implements UsageHistoryFixture, FailingSubcommandGitFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupUsageHistoryFixture()
    }

    // Scenario: cli/task-inspection / Service commits are not rounds
    def "FR14: service commits are not rounds — a salvage commit and a cleanup commit produce no usage rows"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-3', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def implementRound = round('PROJ-3', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50)
        persistRound('PROJ-3', TaskState.atStageStart('implement').recordUnburnedRound(implementRound, ExecutorVersions.none()), 'implement', 0)

        and: 'an uncommitted leftover gets salvaged into a service commit, not a round'
        def worktree = worktreeFor('PROJ-3')
        new File(worktree.toFile(), 'leftover.txt').text = 'half-done work'
        new WorktreeSalvage(runner, worktree, ClaimEpochSource.NONE).salvage('PROJ-3')

        and: 'the task then completes, triggering the cleanup commit that removes .gnomish-task/'
        def repository = taskRepository()
        repository.recordOutcome('PROJ-3',
                new TaskOutcome.Completed(TaskState.atStageStart('implement').recordUnburnedRound(implementRound, ExecutorVersions.none())), TrackerWrite.OWED)
        repository.finishCleanup('PROJ-3')
        assert runner.run(cloneDir, 'cat-file', '-e', registeredId(cloneDir, 'PROJ-3') + ':.gnomish-task').exitCode() != 0

        when:
        def result = (walker.walk(cloneDir, 'PROJ-3') as UsageHistoryResult.Found)

        then: 'only the one real round is a usage row — salvage and cleanup contributed none'
        result.rows().size() == 1
        result.rows()[0].attempt().round() == 0
    }

    // FR19 of define-executor-contract: a commit that rewrites state.json while listing the same
    // record identity records no round — the record already met is not met again.
    def "FR14: a same-stage commit whose attempts list did not grow contributes no extra row"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-6', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def implementRound = round('PROJ-6', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50)
        persistRound('PROJ-6', TaskState.atStageStart('implement').recordUnburnedRound(implementRound, ExecutorVersions.none()), 'implement', 0)

        and: 'state.json is re-committed unchanged in content (attempts list still has exactly one element)'
        def worktree = worktreeFor('PROJ-6')
        def stateJson = worktree.resolve('.gnomish-task').resolve('state.json')
        stateJson.toFile().text = stateJson.toFile().text + ' '
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'no-op re-commit')
        // A factory rewrite of state.json, so registered as its closing push would (design D13).
        registerTip(cloneDir, 'PROJ-6')

        when:
        def result = (walker.walk(cloneDir, 'PROJ-6') as UsageHistoryResult.Found)

        then: 'still exactly one row — the no-op commit did not add a second one'
        result.rows().size() == 1
        result.rows()[0].attempt().round() == 0
    }

    def "FR14: a task branch reachable only via origin is walked the same way, no local branch created"() {
        given: 'a bare origin carrying the task branch, and a fresh single-branch clone that lacks it'
        def bare = initBareRepo(tempDir, 'origin.git')
        def seedRepo = initWorkingRepo(tempDir, 'seed-clone')
        new File(seedRepo.toFile(), 'a.txt').text = 'first'
        runner.run(seedRepo, 'add', 'a.txt')
        runner.run(seedRepo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        FixtureGitConfig.addRemote(seedRepo, 'origin', bare.toString())
        runner.run(seedRepo, 'push', 'origin', 'HEAD:refs/heads/main')

        def seedRegistered = RegisteredCloneFixture.registered(tempDir.resolve('seed-worktrees'), seedRepo)
        new GitTaskRepository(runner, seedRegistered, ClaimEpochSource.NONE, new VirtualClock()).createTask(new TaskContext('PROJ-5', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(seedRepo, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def implementRound = round('PROJ-5', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50)
        new GitAttemptPersistence(runner, seedRegistered.worktrees().resolve('PROJ-5'), 'PROJ-5', ClaimEpochSource.NONE)
                .persist('PROJ-5', TaskState.atStageStart('implement').recordUnburnedRound(implementRound, ExecutorVersions.none()),
                new ToolTrace(new AttemptKey('PROJ-5', 'implement', 0), [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ]))
        runner.run(seedRegistered.worktrees().resolve('PROJ-5'), 'push', 'origin', 'gnomish/PROJ-5')

        def observerClone = tempDir.resolve('observer-clone')
        seedClone(tempDir, bare.toString(), observerClone, '--branch', 'main', '--single-branch')

        when:
        def result = (walker.walk(observerClone, 'PROJ-5') as UsageHistoryResult.Found)

        then:
        result.rows().size() == 1

        and: 'no local branch was created — only the remote-tracking ref from the narrow fetch'
        runner.run(observerClone, 'rev-parse', '--verify', '--quiet', 'refs/heads/gnomish/PROJ-5').exitCode() != 0
        runner.run(observerClone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-5').exitCode() == 0
    }

    // Documents (rather than PIT-kills — see UsageHistoryWalker#isNonBlank's @DoNotMutate javadoc)
    // that a blank line injected ahead of a real commit hash never crashes the walk nor corrupts
    // its result: whether or not the blank-line filter runs, a blank "commit hash" always fails
    // git show with a non-zero exit and contributes no row (this codebase has no Mockito and
    // GitProcessRunner is final, so GitProcessRunner's own gitBinary constructor seam — the same
    // one GitProcessRunnerSpec's "nonexistent binary" scenario relies on — substitutes the
    // committed `log-answer-per-run` stand-in, which answers `log` with what the spec wrote beside
    // it — a blank line ahead of the real commit hash — and delegates every other subcommand to the
    // real git binary unchanged).
    def "FR14: a blank line in git log's output never crashes the walk nor produces a bogus row"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-8', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def implementRound = round('PROJ-8', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50)
        persistRound('PROJ-8', TaskState.atStageStart('implement').recordUnburnedRound(implementRound, ExecutorVersions.none()), 'implement', 0)
        def realCommit = runner.run(cloneDir, 'rev-parse', 'gnomish/PROJ-8').stdout().forParsing().trim()

        def fakeGit = StandIn.recording(tempDir, 'log-answer-per-run')
        StandIn.beside(fakeGit, 'log.out').toFile().text = "\n${realCommit}\n"
        def fakeWalker = new UsageHistoryWalker(new GitProcessRunner(fakeGit.toString()), VirtualTimeGitRetries.gitInfrastructure())

        when:
        def result = (fakeWalker.walk(cloneDir, 'PROJ-8') as UsageHistoryResult.Found)

        then: 'exactly one row — the blank line never surfaces as a bogus row'
        result.rows().size() == 1
        result.rows()[0].attempt().round() == 0
    }

    /**
     * A walker over a stand-in git that fails one subcommand and delegates the rest — the shared
     * {@link FailingSubcommandGitFixture}, also used by {@code GitShowTipTerminationSpec} et al.
     */
    private UsageHistoryWalker walkerFailing(String subcommand) {
        new UsageHistoryWalker(new GitProcessRunner(gitFailingOn(tempDir, subcommand).toString()), VirtualTimeGitRetries.gitInfrastructure())
    }

    // FR5 of harden-logging-observability: a refused commit listing renders as a report with no
    // rounds at all — the same shape a task that never ran produces. Only the WARN separates them.
    def "FR5: a refused commit listing warns before the report renders empty"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-9', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        persistRound('PROJ-9', TaskState.atStageStart('implement')
                .recordUnburnedRound(round('PROJ-9', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50), ExecutorVersions.none()), 'implement', 0)
        def logs = LogCaptureSupport.attach(UsageHistoryWalker, Level.DEBUG)

        when:
        def result = walkerFailing('log').walk(cloneDir, 'PROJ-9') as UsageHistoryResult.Found
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        result.rows().isEmpty()

        and:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.USAGE_HISTORY_LISTING_FAILED.head())
        warnings[0].formattedMessage.contains('could not list the state-touching commits')
        warnings[0].formattedMessage.contains('the report will be empty')
    }

    // FR5: a commit that shows no state.json is the ORDINARY case — the cleanup commit deletes it —
    // so the classification is DEBUG, below the console, and never an alarm. The cleanup commit is
    // also the register of a delivered task, whose baseCommit is read at the Completed commit.
    def "FR5: the cleanup commit, carrying no state.json, is classified at DEBUG, not warned about"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-10', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def passed = TaskState.atStageStart('implement')
                .recordUnburnedRound(round('PROJ-10', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50), ExecutorVersions.none())
        persistRound('PROJ-10', passed, 'implement', 0)
        def repository = taskRepository()
        repository.recordOutcome('PROJ-10', new TaskOutcome.Completed(passed), TrackerWrite.OWED)
        repository.finishCleanup('PROJ-10')
        def cleanup = registeredId(cloneDir, 'PROJ-10')
        def logs = LogCaptureSupport.attach(UsageHistoryWalker, Level.DEBUG)

        when:
        def result = walker.walk(cloneDir, 'PROJ-10') as UsageHistoryResult.Found
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        result.rows().size() == 1

        and:
        events.every { it.level == Level.DEBUG }
        events.any {
            it.formattedMessage.contains(cleanup) && it.formattedMessage.contains('carries no .gnomish-task/state.json')
        }
    }
}
