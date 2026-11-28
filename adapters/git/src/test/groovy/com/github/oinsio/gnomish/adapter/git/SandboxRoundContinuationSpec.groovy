package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.NoOpenRoundException
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.agent.TerminalClose
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10 of extract-agent-round-runner (design D11, D14 as amended 2026-11-25): the box round source
 * holds its open round. Going pending registers the pending-round snapshot and keeps the round, which
 * a poll is handed with its box, token, answer path and listener; on a resuming instance a poll
 * continues the pending round the run's cell was restored with, in the stage's leased box over the
 * cell's token, with no mint — and nothing else is continued.
 */
class SandboxRoundContinuationSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'SBX-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final String STAGE = 'build'

    @TempDir
    Path tempDir

    Path cloneDir
    CurrentRound rounds = new CurrentRound()
    EnvironmentLease lease

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        // The branch starts at a registered closing commit, as STARTED leaves it (design D13).
        gitOutput(cloneDir, 'update-ref', 'refs/gnomish-factory/' + BRANCH.substring('gnomish/'.length()), 'refs/heads/' + BRANCH)
        def boxes = 0
        lease = new EnvironmentLease({
            ->
            new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box' + (boxes++))))
        },
        BRANCH,
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ])
    }

    private static StageDefinition stage() {
        new StageDefinition(
                STAGE, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static StageExecutor.Request request() {
        new StageExecutor.Request(
                new TaskContext(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                stage(), new DirectoryWorkspace(Path.of('.')), 1, [])
    }

    private SandboxRoundEnvironmentSource source() {
        new SandboxRoundEnvironmentSource(lease, new GitProcessRunner(), cloneDir, TASK, rounds, new VirtualClock())
    }

    private String branchTip() {
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
    }

    /** A commit on the task branch: the tip a fresh mint would read. */
    private void moveTip() {
        def tip = 'refs/heads/' + BRANCH
        gitOutput(cloneDir, 'update-ref', tip, gitOutput(cloneDir, 'commit-tree', '-p', tip, '-m', 'moved', tip + '^{tree}'))
    }

    private static String answerPath(RoundToken token) {
        '.gnomish-task/decisions/' + STAGE + '-a1-' + token.commit() + '.json'
    }

    // FR10 (D11 as amended): going pending is the pending-round snapshot, and the round stays open
    def "a start that went pending registers the pending-round snapshot and hands the poll the same round"() {
        given:
        def openTip = branchTip()
        def source = source()
        def round = source.openRound(request())
        new File(((LocalBoxEnvironment) round.environment()).workingCopy.toFile(), 'work.txt').text = 'started'

        when:
        round.goPending()
        moveTip()
        def polled = source.continueRound(request())

        then: 'the snapshot names the pending-round subject and the token the round opened with'
        def snapshot = rounds.snapshot().orElseThrow()
        snapshot.close() == RoundClose.PENDING
        snapshot.token() == RoundToken.of(openTip)
        gitOutput(cloneDir, 'log', '-1', '--format=%s', snapshot.attemptCommit()) ==
                'gnomish: pending-snapshot ' + STAGE + '#1 ' + openTip

        and: 'the poll runs in that very round, nothing minted over the moved tip'
        polled.is(round)
        rounds.opened() == RoundToken.of(openTip)
    }

    // FR10, FR11 (D14 as amended): a resuming instance's poll continues the restored round, unminted
    def "a resuming source continues the pending round its cell was restored with, in the stage's box"() {
        given:
        def token = RoundToken.of(branchTip())
        rounds.open(token)
        rounds.snapshotted(branchTip(), RoundClose.PENDING)
        moveTip()
        def source = source()

        when:
        def polled = source.continueRound(request())

        then:
        polled.environment().is(lease.environmentFor(STAGE))
        polled.answerFilePath().toString() == answerPath(token)
        polled.roundListener() instanceof MidRoundHarvestListener
        rounds.opened() == token

        and: 'it is held until its close'
        source.continueRound(request()).is(polled)

        when:
        polled.closeRound(TerminalClose.UNVERIFIABLE)
        source.continueRound(request())

        then:
        thrown(NoOpenRoundException)
    }

    // FR10: only a pending round is continued; a restored interrupted verification is no round to poll
    def "a cell restored with a round that did not go pending is refused"() {
        given:
        rounds.open(RoundToken.of(branchTip()))
        rounds.snapshotted(branchTip(), RoundClose.VERIFIABLE)

        when:
        source().continueRound(request())

        then:
        thrown(NoOpenRoundException)
    }

    // FR10: a round this source let go is not adopted again, though the cell still names it pending
    def "a pending round this source already let go is not continued again"() {
        given:
        def source = source()
        def round = source.openRound(request())
        round.goPending()
        round.discard()

        when:
        source.continueRound(request())

        then:
        thrown(NoOpenRoundException)
        rounds.snapshot().orElseThrow().close() == RoundClose.PENDING
    }
}
