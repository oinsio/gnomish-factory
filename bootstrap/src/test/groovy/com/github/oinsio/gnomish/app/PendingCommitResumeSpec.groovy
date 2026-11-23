package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.EnvironmentRoundSnapshot
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, FR20, NFR-R1 of migrate-verify-to-executor-contract (design D21, "The resume"; kill window
 * W2): a factory dies after a pending commit of a sandboxed round was registered and before the
 * round's closing commit. On the container medium — a daemon-free box ({@link LocalBoxEnvironment}:
 * a real clone, real in-box commits, a real harvest), the production snapshot step, the production
 * persistence's pending write — the pickup's one recogniser and resume decorator re-verify the
 * snapshot below the pending commit without running the stage executor, the state read at the
 * register through the live path's parse carries the round's entries under the restored round's
 * identity (the entries {@code PendingPoll} continues), and the round's close lands on the pending
 * commit. The host's windows through the engine and the take routes are {@code
 * HostPendingPollKillPointSpec}'s and {@code HostPendingPollOtherCloneSpec}'s.
 */
class PendingCommitResumeSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'SBX-21'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final String STATE = '.gnomish-task/state.json'
    static final int ROUND = 1

    static final StageDefinition STAGE = new StageDefinition('build', 'purpose', [], [],
    new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
    'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    LocalBoxEnvironment box
    GitObjects gitObjects

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        // The registered commit records one burned round: the round about to run is round 1.
        def round0 = AttemptRecords.of(0, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [], ExecutorUsage.none(), [], Stop.none())
        Files.createDirectories(cloneDir.resolve('.gnomish-task'))
        Files.writeString(cloneDir.resolve(STATE), TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(
                        TaskState.atStageStart('build').recordQualityFailure(round0, ExecutorVersions.none()))))
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        gitOutput(cloneDir, 'update-ref', registerRef(), 'refs/heads/' + BRANCH)
        box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize(BRANCH, null)
        gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))
    }

    private static String registerRef() {
        'refs/gnomish-factory/' + BRANCH.substring('gnomish/'.length())
    }

    private String registered() {
        gitOutput(cloneDir, 'rev-parse', registerRef())
    }

    private EnvironmentAttemptPersistence persistence(CurrentRound rounds) {
        new EnvironmentAttemptPersistence(box, runner, cloneDir, gitObjects, rounds, ClaimEpochSource.NONE)
    }

    /** The state at {@code commit}, through the parse the live path's write and every resume use. */
    private TaskState stateAt(String commit) {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(gitOutput(cloneDir, 'show', commit + ':' + STATE))))
    }

    private StageExecutor.Request request() {
        new StageExecutor.Request(new TaskContext(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
        STAGE, new DirectoryWorkspace(tempDir), ROUND, [])
    }

    /** The live round up to its death: opened, worked, snapshotted, one check pending and landed. */
    private PendingPollRecord liveRoundDiesAfterPendingCommit() {
        def live = new CurrentRound()
        live.open(RoundToken.of(registered()))
        new File(box.workingCopy.toFile(), 'work.txt').text = 'gnome work'
        new EnvironmentRoundSnapshot(box, runner, cloneDir, live).snapshot(TASK, 'build', ROUND, RoundClose.VERIFIABLE)
        def entry = new PendingPollRecord(new PendingSubject.Check(new CheckRef(0, UntrustedText.factory('executor:ci'))), ROUND, live.opened(),
                new PendingKey('run-1'), Instant.parse('2026-11-12T09:00:00Z'))
        persistence(live).persistPending(TASK, stateAt(registered()).withPendingPolls([entry]))
        entry
    }

    // Scenario: git/git-task-persistence / Interrupted verification resumes through a pending commit
    // Scenario: verification/pending-poll / A pending entry names no outcome
    def "FR3, FR20: a pickup of a registered pending commit re-verifies the snapshot below it, runs no stage executor and burns no attempt"() {
        given: 'the snapshot the round registered, and its state'
        def entry = liveRoundDiesAfterPendingCommit()
        def pendingCommit = registered()
        def snapshot = gitOutput(cloneDir, 'rev-parse', pendingCommit + '^')
        def below = stateAt(snapshot)

        when: 'a pickup reads the register and runs the round through the resume decorator'
        def found = new SnapshotTipCheck(runner, cloneDir).inspect(TASK)
        def resumed = new CurrentRound()
        def runs = 0
        def stageExecutor = { StageExecutor.Request r ->
            runs++; throw new AssertionError('the stage executor ran')
        } as StageExecutor
        def result = new ResumeVerificationStageExecutor(stageExecutor, resumed, found.orElse(null)).execute(request())

        then: 'no stage executor ran; the round goes on to verification of exactly the snapshot'
        runs == 0
        result.status() instanceof ExecutionResult.Completed
        resumed.closed().attemptCommit() == snapshot
        resumed.closed().registered() == pendingCommit

        and: 'the pending commit names no outcome: attempts, position and counter are the snapshot\'s'
        def state = stateAt(pendingCommit)
        state.attempts() == below.attempts()
        state.position() == below.position()
        state.attemptsUsed() == below.attemptsUsed()

        and: 'its entries are the round\'s own by identity — the attempt the round runs under, the restored token'
        state.pendingPolls() == [entry]
        entry.attempt() == state.attempts().size()
        resumed.takeToken() == Optional.of(entry.token())
    }

    // D21, "The parent of the round's close": the restored registered commit is what the box's
    // HEAD stands on, so the in-box state commit's parent check accepts the pickup's close
    def "FR20: the resumed round's close sits on the pending commit and carries no pending entry"() {
        given:
        liveRoundDiesAfterPendingCommit()
        def pendingCommit = registered()
        def resumed = new CurrentRound()
        new ResumeVerificationStageExecutor({
            throw new AssertionError('ran')
        } as StageExecutor, resumed,
        new SnapshotTipCheck(runner, cloneDir).inspect(TASK).get()).execute(request())
        def key = new AttemptKey(TASK, 'build', ROUND)
        def closing = stateAt(pendingCommit).withPendingPolls([])
        .recordQualityFailure(AttemptRecords.of(ROUND, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [],
        ExecutorUsage.none(), [], Stop.none()), ExecutorVersions.none())

        when:
        persistence(resumed).persist(TASK, closing, new ToolTrace(key, []))

        then:
        registered() != pendingCommit
        gitOutput(cloneDir, 'rev-parse', registered() + '^') == pendingCommit
        stateAt(registered()).pendingPolls().isEmpty()
    }
}
