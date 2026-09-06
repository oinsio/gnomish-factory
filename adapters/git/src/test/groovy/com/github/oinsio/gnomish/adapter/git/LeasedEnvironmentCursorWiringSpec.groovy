package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import com.github.oinsio.gnomish.sandbox.environment.LeasedEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * M4, FR6 of fix-denial-attribution-durability: the denial-cursor round trip driven through the
 * PRODUCTION wiring — {@link EnvironmentAttemptPersistence} over the real {@link
 * LeasedEnvironment} view the composition root builds it over, not over a hand-rolled double that
 * happens to implement the port's denial methods itself.
 *
 * <p>This is the spec whose absence let the predecessor's cursor feature ship dead: the view
 * forwarded none of the port's three denial defaults, so production always read the interface's
 * empty constants while both halves of the feature's own specs stayed green over doubles.
 */
class LeasedEnvironmentCursorWiringSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'WIRE-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final DenialCursor COMMITTED =
    new DenialCursor('sha256:guard-container', '2026-08-28T10:00:00.000000001Z')

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    LocalBoxEnvironment box
    LeasedEnvironment leased
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
        // The production seam: collaborators are wired over the leased VIEW of the environment of
        // the stage in flight, exactly as ContainerRunSupport.persistence() builds them.
        leased = new LeasedEnvironment({ box })
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))
        snapshotStep = new EnvironmentRoundSnapshot(box, runner, cloneDir, TASK, attemptRef)
        persistence = new EnvironmentAttemptPersistence(
                leased, runner, cloneDir, gitObjects, TASK, attemptRef, ClaimEpochSource.NONE)
    }

    private void closeRound(int round) {
        new File(box.workingCopy.toFile(), 'work.txt').text = 'gnome work ' + round
        snapshotStep.snapshot(TASK, 'implement', round)
        persistence.persist(TASK, TaskState.atStageStart('implement'), new ToolTrace(
                        new AttemptKey(TASK, 'implement', round),
                        [
                            new ToolCall(0, 'bash', Instant.parse('2026-08-28T09:00:00Z'), Duration.ofMillis(100))
                        ]))
    }

    private committedCursor() {
        StateJsonMapper.readDto(
                gitOutput(cloneDir, 'show', gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
                + ':.gnomish-task/state.json')).egressCursor()
    }

    // FR6, M4: the position the guard actually holds reaches the state commit THROUGH the leased
    //     view — with the forward missing this read answered Optional.empty() and committed nothing.
    def "the cursor read through the leased view lands in the state commit"() {
        given:
        box.denialCursor = COMMITTED

        when:
        closeRound(1)

        then:
        committedCursor().source() == COMMITTED.source()
        committedCursor().position() == COMMITTED.position()
    }

    // FR6, M4: the restore half of the round trip, driven through the same view — the offer must
    //     reach the box, and the position it left there must be what the next round commits.
    def "a cursor restored through the leased view positions the box and is committed by the next round"() {
        when: 'a resuming instance offers the position recorded at the branch tip'
        leased.restoreDenials(DenialRestoration.at(COMMITTED))

        then: 'the offer reached the leased environment, not the view'
        box.denialCursor == COMMITTED

        when: 'the resumed lease closes its first round'
        closeRound(1)

        then: 'the restored position is the one the round commits, so the delta continues from it'
        committedCursor().position() == COMMITTED.position()
    }

    // FR6: an environment holding no position commits none — the forward reports the box's real
    //     answer, and "no cursor" stays distinguishable from "the view swallowed the question".
    def "a box with no denial source commits no cursor through the view"() {
        when:
        closeRound(1)

        then:
        committedCursor() == null

        and: "the denial delta read through the view is the box's own, still empty"
        leased.readDenials().denials().isEmpty()
    }
}
