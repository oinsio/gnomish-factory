package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.SeedTransferFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.LocalTaskBranch
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The identity spec of design D15 of define-executor-contract (D10 single-owner table, row {@code
 * UsageFold}): "the fold of the walked rounds and the tip's {@code totals} are one". Component specs
 * prove the round writers fold through {@code UsageFold} and the walk collects every round record;
 * only a flow over a bare origin and the real adapters — the lifecycle store, the round
 * persistence, the history walk and {@code gnomish usage} — proves the two figures meet after a
 * pass, a failure, a decision reset and a resume by another instance. Rounds carry a priced
 * executor and an unpriced judge vote, so the cost and its lower bound travel the medium too.
 *
 * <p>FR18 of define-executor-contract.
 */
class UsageSnapshotIdentitySpec extends Specification implements BareGitRepoFixture, SeedTransferFixture {

    static final String TASK = 'PROJ-7'

    @TempDir
    Path tempDir

    TaskGit git = TaskGitFixture.realClaimless()
    Path cloneDir
    RegisteredClone registered
    int minted = 0

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        commit(cloneDir, 'seed.txt', 'seed')
        addConvergedOrigin(cloneDir, tempDir)
        registered = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        git.store().taskRepository(registered).createTask(
                new TaskContext(TASK, UntrustedText.tracker('T'), UntrustedText.tracker('B'), []),
                TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
    }

    def "FR18: after a pass the fold of the walked rounds is the tip's totals"() {
        when:
        persist(registered, TaskState.atStageStart('implement')
                .recordPassAndAdvance(round('implement', 0, AttemptRecord.Result.PASSED), review(), ExecutorVersions.none()))

        then:
        identityHolds(cloneDir, registered, 1)
    }

    def "FR18: after a quality failure and a pass both rounds are in the fold and in the tip's totals"() {
        when:
        def failed = TaskState.atStageStart('implement')
                .recordQualityFailure(round('implement', 0, AttemptRecord.Result.QUALITY_FAILURE), ExecutorVersions.none())
        persist(registered, failed)
        persist(registered, failed.recordPassAndAdvance(round('implement', 1, AttemptRecord.Result.PASSED), review(),
                ExecutorVersions.none()))

        then:
        identityHolds(cloneDir, registered, 2)
    }

    def "FR18: a decision reset empties the attempts, and the walk still meets the totals it carried"() {
        given: 'a failed round, escalated'
        def failed = escalatedAfterFailure()

        when: 'the decision resets the stage, and the stage restarts at round 0 under a new token'
        git.store().taskRepository(registered).appendDecision(TASK, new Decision('go on', null, null, null),
                failed.resetAttempts())
        persist(registered, failed.resetAttempts()
                .recordPassAndAdvance(round('implement', 0, AttemptRecord.Result.PASSED), review(), ExecutorVersions.none()))

        then:
        identityHolds(cloneDir, registered, 2)
    }

    def "FR18: a resume by a second instance's clone of origin folds onto the totals the first recorded"() {
        given: 'a failed round, escalated and on origin'
        escalatedAfterFailure()

        and: 'another instance clones origin, adopts the branch and opens its own worktree'
        Path otherClone = seedClone(tempDir, tempDir.resolve('origin.git').toString(), tempDir.resolve('other'))
        RegisteredClone other = RegisteredCloneFixture.registered(tempDir.resolve('other-home'), otherClone)
        def branch = git.branches().adopt(otherClone, TASK, git.branches().locate(otherClone, TASK)) as LocalTaskBranch
        Path worktree = git.worktrees().ensureWorktree(other, branch)
        TaskState pickedUp = git.store().readRecordedState(worktree, TASK).get()

        when: 'it consumes the escalation and passes the stage'
        git.store().taskRepository(other).resumeFrom(TASK, pickedUp.resetAttempts())
        persist(other, pickedUp.resetAttempts()
                .recordPassAndAdvance(round('implement', 0, AttemptRecord.Result.PASSED), review(), ExecutorVersions.none()))

        then: 'the second instance\'s usage walks both instances\' rounds onto one figure'
        identityHolds(otherClone, other, 2)
    }

    private TaskState escalatedAfterFailure() {
        def failed = TaskState.atStageStart('implement')
                .recordQualityFailure(round('implement', 0, AttemptRecord.Result.QUALITY_FAILURE), ExecutorVersions.none())
        persist(registered, failed)
        git.store().taskRepository(registered).recordOutcome(TASK,
                new TaskOutcome.Escalated(failed, new EscalationReport.AttemptsExhausted(1)), TrackerWrite.NONE)
        failed
    }

    /** The identity, three ways: the walk's recorded figure, the fold of its rows, the command. */
    private boolean identityHolds(Path clone, RegisteredClone at, int rounds) {
        def recorded = git.store().readRecordedState(TaskWorktreePath.resolve(at, TASK), TASK).get().totals()
        def found = git.store().usageHistory(clone, TASK) as UsageHistoryResult.Found
        assert found.recordedTotals() == recorded
        assert UsageFold.foldAll(found.rows()*.attempt()) == recorded
        assert recorded.rounds() == rounds
        assert !recorded.cost().complete()
        assert recorded.cost().priced() == new BigDecimal('0.0125') * rounds
        def console = new ScriptedConsoleIO()
        new UsageCommand(git, RegisteredCloneFixture.scope(at), console, new VirtualClock())
                .run(new DefaultApplicationArguments('usage', '--dir=' + clone, TASK))
        assert console.printed.size() == 1 && console.printed[0].contains("rounds=${rounds} ")
        true
    }

    /** The round persistence a round writer drives, then the closing push the run owes origin. */
    private void persist(RegisteredClone at, TaskState state) {
        Path worktree = TaskWorktreePath.resolve(at, TASK)
        AttemptRecord last = state.attempts().last()
        git.store().attemptPersistence(worktree, TASK)
                .persist(TASK, state, new ToolTrace(new AttemptKey(TASK, last.stage(), last.round()), []))
        git.branches().pushClosingBestEffort(worktree, TASK)
    }

    /** A round on a token of its own: a priced executor and one unpriced judge vote. */
    private AttemptRecord round(String stage, int number, AttemptRecord.Result result) {
        minted++
        def executor = Participants.transform('agent-cli', new ExecutorUsage(Duration.ofSeconds(3), [],
        ['claude-x': new ModelUsage(new TokenUsage(100 * minted, 10, 5, 1), new BigDecimal('0.0125'))]))
        def vote = Participants.verdict('agent-cli', 0, ['claude-j': new ModelUsage(new TokenUsage(7, 3, 0, 0), null)])
        AttemptRecords.identified(TASK, stage, RoundToken.of(String.format('%040x', minted)), number, result,
                Instant.parse('2026-07-18T09:00:00Z').plusSeconds(60 * minted), [], [executor, vote], [], Stop.none())
    }

    private static Position review() {
        new Position.AtStage('review')
    }
}
