package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.PendingState
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The host round both host snapshot specs drive (design D22 of migrate-verify-to-executor-contract):
 * a registered task's worktree, the run's one cell opened over its tip, the production host-git
 * decoration's stage chain over a stage executor doing the gnome's work, and the production
 * persistence over the same cell.
 */
trait HostSnapshotRound implements RegisteredRoundFixture {

    CurrentRound cell = new CurrentRound()

    static final StageDefinition STAGE = new StageDefinition(
    'implement', 'purpose', [], [],
    new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
    'instructions.md', [],
    new AutonomyLimits(3), AdvancementMode.AUTO)

    static final int ROUND = 1

    void setupHostRound() {
        setupRegisteredRound()
        // The round opens over the worktree tip, as the git-mode host round source opens it.
        cell.open(RoundToken.of(gitOutput(worktree(), 'rev-parse', 'HEAD')))
    }

    /** The host stage chain as the git-mode host run decorates it, over a stage executor doing {@code work}. */
    ExecutionResult runRound(ExecutionResult answer, GitRunner git = runner, ClaimEpochSource epochs = ClaimEpochSource.NONE, Closure<?> work) {
        def stageExecutor = { StageExecutor.Request request ->
            work.call()
            answer
        } as StageExecutor
        def chain = new GitHostRounds(git, new VirtualClock(), epochs).snapshotting(stageExecutor, cell)
        chain.execute(new StageExecutor.Request(
                        new TaskContext(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                        STAGE, new DirectoryWorkspace(worktree()), ROUND, []))
    }

    /**
     * A poll of the round through the same host stage chain, over a stage executor that answers a poll
     * of {@code turn} with {@code answer} after doing {@code work}, and refuses a start.
     */
    ExecutionResult pollRound(ExecutionResult answer, PendingTurn turn, Closure<?> work) {
        // A map coercion: a trait cannot hold an anonymous class.
        def stageExecutor = [
            execute: { StageExecutor.Request request ->
                throw new IllegalStateException('a poll reached the stage executor as a start')
            },
            poll: { StageExecutor.Request request, PendingTurn polled ->
                assert polled == turn
                work.call()
                answer
            }
        ] as StageExecutor
        def chain = new GitHostRounds(runner, new VirtualClock(), ClaimEpochSource.NONE).snapshotting(stageExecutor, cell)
        chain.poll(new StageExecutor.Request(
                        new TaskContext(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                        STAGE, new DirectoryWorkspace(worktree()), ROUND, []), turn)
    }

    /** The round's close through the production persistence over the run's cell. */
    void persistRound() {
        def key = new AttemptKey(TASK, 'implement', ROUND)
        persistence().persist(TASK, RoundStates.recorded(key), new ToolTrace(key, []))
    }

    /** The production host persistence over the run's cell. */
    GitAttemptPersistence persistence() {
        new GitAttemptPersistence(runner, worktree(), TASK, ClaimEpochSource.NONE, cell, new VirtualClock())
    }

    /**
     * An open round's pending write through the production persistence (design D21): the recorded
     * state with the round's entries, one per named key, each of the cell's open round.
     */
    void persistPending(String... keys) {
        def recorded = store.readRecordedState(worktree(), TASK).orElseThrow()
        def entries = keys.toList().withIndex().collect { String key, int index ->
            new PendingPollRecord(new PendingSubject.Check(new CheckRef(index, UntrustedText.factory('executor:ci'))), ROUND, cell.opened(),
            new PendingKey(key), Instant.parse('2026-11-12T09:00:00Z'))
        }
        persistence().persistPending(TASK, recorded.withPendingPolls(entries))
    }

    /** The round's own pending write (design D11 of extract-agent-round-runner): one {@code Round} entry. */
    void persistPendingRound(String key) {
        def recorded = store.readRecordedState(worktree(), TASK).orElseThrow()
        persistence().persistPending(TASK, recorded.withPendingPolls([
            new PendingPollRecord(PendingSubject.ROUND, ROUND, cell.opened(), new PendingKey(key), Instant.parse('2026-11-24T09:00:00Z'))
        ]))
    }

    static ExecutionResult pending(String key) {
        new ExecutionResult(new ExecutionResult.Pending(new PendingKey(key), null, PendingState.RUNNING, null, null),
                null, ExecutorUsage.none(), new ToolTrace(new AttemptKey(TASK, 'implement', ROUND), []), [])
    }

    static ExecutionResult completed() {
        ExecutionResult.completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey(TASK, 'implement', ROUND), []), [])
    }

    static ExecutionResult failed() {
        new ExecutionResult(new ExecutionResult.Failed(ExecutionResult.FailureClass.QUALITY, UntrustedText.agent('red'), [], null),
        null, ExecutorUsage.none(), new ToolTrace(new AttemptKey(TASK, 'implement', ROUND), []), [])
    }

    static ExecutionResult asking() {
        ExecutionResult.decisionNeeded(UntrustedText.agent('which?'), [UntrustedText.agent('a')], ExecutorUsage.none(),
        new ToolTrace(new AttemptKey(TASK, 'implement', ROUND), []), [])
    }

    void gnomeWorks() {
        commit(worktree(), 'App.java', 'class App {}')
        Files.writeString(worktree().resolve('notes.md'), 'left on disk')
    }

    String tip() {
        gitOutput(cloneDir, 'rev-parse', TIP_REF)
    }

    String fileAt(String rev, String path) {
        gitOutput(cloneDir, 'show', "${rev}:${path}")
    }

    List<String> changedIn(String commit) {
        gitOutput(cloneDir, 'show', '--name-only', '--format=', commit).readLines().findAll {
            !it.isBlank()
        }
    }
}
