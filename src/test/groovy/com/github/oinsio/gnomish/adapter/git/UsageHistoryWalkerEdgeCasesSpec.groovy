package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, NFR-C1 of add-git-workflow: {@link UsageHistoryWalker} edge cases — service commits
 * (salvage/cleanup), no-op re-commits, position transitions to pipeline end, remote-only branches,
 * and a defensive blank-line filter. Core round-detection scenarios live in
 * {@link UsageHistoryWalkerSpec}; split out to keep both files under the 200-line cap
 * (.claude/rules/process-invariants.md).
 */
class UsageHistoryWalkerEdgeCasesSpec extends Specification implements UsageHistoryFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupUsageHistoryFixture()
    }

    def "FR14: service commits are not rounds — a salvage commit and a cleanup commit produce no usage rows"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-3', 'T', 'B', []), null)
        def implementRound = round(0, AttemptRecord.Result.PASSED, 500, 50)
        persistRound('PROJ-3', TaskState.atStageStart('implement').recordUnburnedRound(implementRound), 'implement', 0)

        and: 'an uncommitted leftover gets salvaged into a service commit, not a round'
        def worktree = worktreeFor('PROJ-3')
        new File(worktree.toFile(), 'leftover.txt').text = 'half-done work'
        new WorktreeSalvage(runner, worktree).salvage('PROJ-3')

        and: 'the task then completes, triggering the cleanup commit that removes .gnomish-task/'
        taskRepository().recordOutcome('PROJ-3',
                new TaskOutcome.Completed(TaskState.atStageStart('implement').recordUnburnedRound(implementRound)))

        when:
        def result = (walker.walk(cloneDir, 'PROJ-3') as UsageHistoryResult.Found)

        then: 'only the one real round is a usage row — salvage and cleanup contributed none'
        result.rows().size() == 1
        result.rows()[0].attempt().round() == 0
    }

    // PIT ConditionalsBoundaryMutator on detectNewRound's `attempts.size() <= previous.attempts().size()`:
    // an extra commit at the very same position with an unchanged (same-size) attempts list must
    // NOT be seen as a new round — proving the boundary is <=, not <.
    def "FR14: a same-stage commit whose attempts list did not grow contributes no extra row"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-6', 'T', 'B', []), null)
        def implementRound = round(0, AttemptRecord.Result.PASSED, 500, 50)
        persistRound('PROJ-6', TaskState.atStageStart('implement').recordUnburnedRound(implementRound), 'implement', 0)

        and: 'state.json is re-committed unchanged in content (attempts list still has exactly one element)'
        def worktree = worktreeFor('PROJ-6')
        def stateJson = worktree.resolve('.gnomish-task').resolve('state.json')
        stateJson.toFile().text = stateJson.toFile().text + ' '
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'no-op re-commit')

        when:
        def result = (walker.walk(cloneDir, 'PROJ-6') as UsageHistoryResult.Found)

        then: 'still exactly one row — the no-op commit did not add a second one'
        result.rows().size() == 1
        result.rows()[0].attempt().round() == 0
    }

    // PIT NegateConditionalsMutator/BooleanFalseReturnValsMutator on samePosition: a transition
    // from AtStage to PipelineEnd must be treated as a *different* position (not same), producing
    // a new row rather than being folded away as "no new round".
    def "FR14: advancing from a stage to pipeline end starts a fresh round and still yields a row for it"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-7', 'T', 'B', []), null)
        def implementRound = round(0, AttemptRecord.Result.PASSED, 500, 50)
        persistRound('PROJ-7', TaskState.atStageStart('implement').recordUnburnedRound(implementRound), 'implement', 0)

        def endRound = round(0, AttemptRecord.Result.PASSED, 300, 30)
        def endState = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())
        .recordUnburnedRound(endRound)
        persistRound('PROJ-7', endState, '(pipeline end)', 0)

        when:
        def result = (walker.walk(cloneDir, 'PROJ-7') as UsageHistoryResult.Found)

        then:
        result.rows().size() == 2
        result.rows()[0].stage() == 'implement'
        result.rows()[1].stage() == '(pipeline end)'
    }

    def "FR14: a task branch reachable only via origin is walked the same way, no local branch created"() {
        given: 'a bare origin carrying the task branch, and a fresh single-branch clone that lacks it'
        def bare = initBareRepo(tempDir, 'origin.git')
        def seedClone = initWorkingRepo(tempDir, 'seed-clone')
        new File(seedClone.toFile(), 'a.txt').text = 'first'
        runner.run(seedClone, 'add', 'a.txt')
        runner.run(seedClone, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(seedClone, 'remote', 'add', 'origin', bare.toString())
        runner.run(seedClone, 'push', 'origin', 'HEAD:refs/heads/main')

        def seedWorktrees = tempDir.resolve('seed-worktrees')
        new GitTaskRepository(runner, seedClone, seedWorktrees).createTask(new TaskContext('PROJ-5', 'T', 'B', []), null)
        def implementRound = round(0, AttemptRecord.Result.PASSED, 500, 50)
        new GitAttemptPersistence(runner, seedWorktrees.resolve('seed-clone').resolve('PROJ-5'), 'PROJ-5')
                .persist('PROJ-5', TaskState.atStageStart('implement').recordUnburnedRound(implementRound),
                new ToolTrace(new AttemptKey('PROJ-5', 'implement', 0), [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ]))
        runner.run(seedWorktrees.resolve('seed-clone').resolve('PROJ-5'), 'push', 'origin', 'gnomish/PROJ-5')

        def observerClone = tempDir.resolve('observer-clone')
        def cloneResult = runner.run(
                tempDir, 'clone', '--branch', 'main', '--single-branch', bare.toString(), observerClone.toString())
        assert cloneResult.exitCode() == 0: "clone failed: ${cloneResult.stderr()}"

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
    // one GitProcessRunnerSpec's "nonexistent binary" scenario relies on — substitutes a script
    // that injects a blank line ahead of the real commit hash for `log` only, delegating every
    // other subcommand to the real git binary unchanged).
    def "FR14: a blank line in git log's output never crashes the walk nor produces a bogus row"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-8', 'T', 'B', []), null)
        def implementRound = round(0, AttemptRecord.Result.PASSED, 500, 50)
        persistRound('PROJ-8', TaskState.atStageStart('implement').recordUnburnedRound(implementRound), 'implement', 0)
        def realCommit = runner.run(cloneDir, 'rev-parse', 'gnomish/PROJ-8').stdout().trim()

        def fakeGit = tempDir.resolve('fake-git.sh')
        fakeGit.toFile().text = """#!/bin/sh
if [ "\$1" = "log" ]; then
  printf '\\n${realCommit}\\n'
  exit 0
fi
exec git "\$@"
"""
        fakeGit.toFile().setExecutable(true)
        def fakeWalker = new UsageHistoryWalker(new GitProcessRunner(fakeGit.toString()))

        when:
        def result = (fakeWalker.walk(cloneDir, 'PROJ-8') as UsageHistoryResult.Found)

        then: 'exactly one row — the blank line never surfaces as a bogus row'
        result.rows().size() == 1
        result.rows()[0].attempt().round() == 0
    }
}
