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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, FR22, FR23 of add-sandbox-core (design D15, D16, D17): the
 * snapshot-first sandboxed round protocol on local repositories — snapshot
 * commit then state commit, the parent-check against daemon-inserted commits,
 * the byte-exact read-back against in-box tampering, the decision-file
 * carve-out with stale-name exclusion, and the snapshot-without-state resume
 * classification that re-verifies without burning an attempt.
 */
class EnvironmentRoundProtocolSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'PROT-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    LocalBoxEnvironment box
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
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))
        snapshotStep = new EnvironmentRoundSnapshot(box, runner, cloneDir, TASK, attemptRef)
        persistence = new EnvironmentAttemptPersistence(box, runner, cloneDir, gitObjects, TASK, attemptRef, ClaimEpochSource.NONE)
    }

    private void gnomeWork(String file = 'work.txt', String content = 'gnome work') {
        new File(box.workingCopy.toFile(), file).text = content
    }

    private void gnomeCommit(String message = 'gnome commit') {
        runner.run(box.workingCopy, 'add', '-A')
        runner.run(box.workingCopy, 'commit', '-m', message)
    }

    private static TaskState sampleState() {
        TaskState.atStageStart('implement')
    }

    private static ToolTrace sampleTrace(int round) {
        new ToolTrace(new AttemptKey(TASK, 'implement', round), [
            new ToolCall(0, 'bash', Instant.parse('2026-08-08T09:00:00Z'), Duration.ofMillis(100))
        ])
    }

    private String factoryTip() {
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
    }

    def "FR21: a sandboxed round closes as a snapshot commit plus a state commit on the factory branch"() {
        given:
        gnomeWork()

        when: 'the executor tail snapshots and the engine persists after verification'
        def attempt = snapshotStep.snapshot(TASK, 'implement', 1)
        persistence.persist(TASK, sampleState(), sampleTrace(1))

        then: 'the branch gained snapshot then state, state commit parented on the snapshot'
        def tip = factoryTip()
        gitOutput(cloneDir, 'log', '-1', '--format=%s', tip) == 'gnomish: round implement#1'
        gitOutput(cloneDir, 'rev-parse', tip + '^') == attempt
        gitOutput(cloneDir, 'log', '-1', '--format=%s', attempt) == 'gnomish: snapshot implement#1'

        and: 'the attempt commit carries the gnome work, readable as bare objects'
        gitOutput(cloneDir, 'show', attempt + ':work.txt') == 'gnome work'

        and: 'state.json and the trace live in the state commit only'
        gitOutput(cloneDir, 'show', tip + ':.gnomish-task/state.json').contains('implement')
        gitOutput(cloneDir, 'show', tip + ':.gnomish-task/attempts/implement/1/trace.jsonl').contains('bash')
        runner.run(cloneDir, 'rev-parse', attempt + ':.gnomish-task/state.json').exitCode() != 0
    }

    // FR5 of fix-denial-report-attachment: the position delimiting this round's denials is
    //     committed with the round itself, so a resuming instance continues the delta from it
    //     instead of replaying the guard container's whole surviving log onto its first round
    def "FR5: the state commit carries the environment's denial cursor"() {
        given: 'a box whose denial source is at a known position'
        box.denialCursor = new DenialCursor('sha256:guard-container', '2026-08-19T10:00:00.000000001Z')

        when:
        snapshotStep.snapshot(TASK, 'implement', 1)
        persistence.persist(TASK, sampleState(), sampleTrace(1))

        then: 'state.json records both the position and the source it belongs to'
        def committed = StateJsonMapper.readDto(
                gitOutput(cloneDir, 'show', factoryTip() + ':.gnomish-task/state.json'))
        committed.egressCursor().source() == 'sha256:guard-container'
        committed.egressCursor().position() == '2026-08-19T10:00:00.000000001Z'
    }

    def "FR5: a round whose environment has no denial source records no cursor"() {
        when:
        snapshotStep.snapshot(TASK, 'implement', 1)
        persistence.persist(TASK, sampleState(), sampleTrace(1))

        then:
        StateJsonMapper.readDto(gitOutput(cloneDir, 'show', factoryTip() + ':.gnomish-task/state.json'))
                .egressCursor() == null
    }

    def "FR21: a round that changed nothing still closes with a distinct attempt commit"() {
        when:
        def attempt = snapshotStep.snapshot(TASK, 'implement', 1)

        then:
        attempt != gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH + '^') || attempt == factoryTip()
        gitOutput(cloneDir, 'log', '-1', '--format=%s', attempt) == 'gnomish: snapshot implement#1'
    }

    def "FR21: the gnome's own commits are preserved under the snapshot"() {
        given:
        gnomeWork('a.txt', 'one')
        gnomeCommit('gnome step 1')
        gnomeWork('b.txt', 'two')

        when:
        def attempt = snapshotStep.snapshot(TASK, 'implement', 1)

        then: 'the snapshot builds on the gnome commit and both reach the factory clone'
        gitOutput(cloneDir, 'log', '--format=%s', attempt).readLines().take(2) == [
            'gnomish: snapshot implement#1',
            'gnome step 1',
        ]
    }

    def "FR22: a commit inserted in-box between snapshot and state commit aborts as a boundary violation"() {
        given:
        gnomeWork()
        snapshotStep.snapshot(TASK, 'implement', 1)

        and: 'a background daemon sneaks a commit onto the branch'
        gnomeWork('daemon.txt', 'sneaky')
        gnomeCommit('daemon commit')

        when:
        persistence.persist(TASK, sampleState(), sampleTrace(1))

        then:
        def ex = thrown(RoundBoundaryViolationException)
        ex.message.contains('is not the snapshot commit')
    }

    def "FR22: in-box tampering with state.json between putFile and commit aborts by read-back"() {
        given: 'a box whose file channel is compromised: written bytes are altered in place'
        def tamperingBox = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('tbox'))) {
                    @Override
                    void putFile(String path, byte[] content) {
                        def bytes = path.endsWith('state.json')
                                ? new String(content, StandardCharsets.UTF_8).replace('implement', 'tampered').getBytes(StandardCharsets.UTF_8)
                                : content
                        super.putFile(path, bytes)
                    }
                }
        tamperingBox.materialize(BRANCH, null)
        def snapshot2 = new EnvironmentRoundSnapshot(tamperingBox, runner, cloneDir, TASK, attemptRef)
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp2')))
        def persistence2 = new EnvironmentAttemptPersistence(tamperingBox, runner, cloneDir, gitObjects, TASK, attemptRef, ClaimEpochSource.NONE)
        snapshot2.snapshot(TASK, 'implement', 1)

        when:
        persistence2.persist(TASK, sampleState(), sampleTrace(1))

        then:
        def ex = thrown(RoundBoundaryViolationException)
        ex.message.contains('differs from what the factory wrote')
    }

    def "FR22: in-box tampering with the trace file between putFile and commit aborts by its own read-back"() {
        given: 'a box whose file channel tampers only the trace file, leaving state.json untouched'
        def tamperingBox = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('tbox3'))) {
                    @Override
                    void putFile(String path, byte[] content) {
                        // Same-length replacement so the read-back cap (written.length + 1) is not
                        // itself tripped first — this test targets the byte-mismatch branch, not the
                        // size-cap branch.
                        def bytes = path.endsWith('trace.jsonl')
                                ? new String(content, StandardCharsets.UTF_8).replace('bash', 'crab').getBytes(StandardCharsets.UTF_8)
                                : content
                        super.putFile(path, bytes)
                    }
                }
        tamperingBox.materialize(BRANCH, null)
        def snapshot2 = new EnvironmentRoundSnapshot(tamperingBox, runner, cloneDir, TASK, attemptRef)
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp3')))
        def persistence2 = new EnvironmentAttemptPersistence(tamperingBox, runner, cloneDir, gitObjects, TASK, attemptRef, ClaimEpochSource.NONE)
        snapshot2.snapshot(TASK, 'implement', 1)

        when:
        persistence2.persist(TASK, sampleState(), sampleTrace(1))

        then: 'the trace read-back catches the tampering even though the state.json read-back passed'
        def ex = thrown(RoundBoundaryViolationException)
        ex.message.contains('differs from what the factory wrote')
        ex.message.contains('trace.jsonl')
    }

    def "FR23: the current round's decision file is the one permitted state-directory write"() {
        given: 'the gnome leaves a decision request for exactly this stage and attempt'
        gnomeWork()
        def decisions = new File(box.workingCopy.toFile(), '.gnomish-task/decisions')
        decisions.mkdirs()
        new File(decisions, 'implement-a1.json').text = '{"question":"which db?"}'

        when:
        def attempt = snapshotStep.snapshot(TASK, 'implement', 1)
        persistence.persist(TASK, sampleState(), sampleTrace(1))

        then: 'boundary verification passes and the decision rides the snapshot commit'
        gitOutput(cloneDir, 'show', attempt + ':.gnomish-task/decisions/implement-a1.json').contains('which db?')
    }

    def "FR23: a stale-named decision file is still a violation"() {
        given: 'a decision file for some other attempt'
        def decisions = new File(box.workingCopy.toFile(), '.gnomish-task/decisions')
        decisions.mkdirs()
        new File(decisions, 'implement-a9.json').text = '{"question":"stale"}'
        snapshotStep.snapshot(TASK, 'implement', 1)

        when:
        persistence.persist(TASK, sampleState(), sampleTrace(1))

        then:
        def ex = thrown(RoundBoundaryViolationException)
        ex.message.contains('implement-a9.json')
    }

    def "FR21: any other gnome write under .gnomish-task/ aborts"() {
        given:
        def stateDir = new File(box.workingCopy.toFile(), '.gnomish-task')
        stateDir.mkdirs()
        new File(stateDir, 'state.json').text = '{"forged":true}'
        snapshotStep.snapshot(TASK, 'implement', 1)

        when:
        persistence.persist(TASK, sampleState(), sampleTrace(1))

        then:
        thrown(RoundBoundaryViolationException)
    }

    def "FR21: a snapshot without a state commit classifies as interrupted verification, no attempt burned"() {
        given:
        gnomeWork()
        def attempt = snapshotStep.snapshot(TASK, 'implement', 2)

        expect: 'resume sees the pending verification with its exact attempt commit'
        def check = new SnapshotTipCheck(runner, cloneDir)
        with(check.inspect(BRANCH).get()) {
            attemptCommit() == attempt
            stage() == 'implement'
            round() == 2
        }

        when: 'the state commit lands'
        persistence.persist(TASK, sampleState(), sampleTrace(2))

        then: 'the tip is no longer an interrupted verification'
        check.inspect(BRANCH).isEmpty()
    }

    def "FR22: a rewritten in-box history is refused at the state-commit harvest too"() {
        given:
        gnomeWork()
        snapshotStep.snapshot(TASK, 'implement', 1)
        runner.run(box.workingCopy, 'commit', '--amend', '-m', 'rewritten snapshot')

        when:
        persistence.persist(TASK, sampleState(), sampleTrace(1))

        then:
        thrown(HarvestRefusedException)
    }
}
