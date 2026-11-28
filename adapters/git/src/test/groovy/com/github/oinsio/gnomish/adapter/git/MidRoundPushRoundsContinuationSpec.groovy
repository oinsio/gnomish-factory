package com.github.oinsio.gnomish.adapter.git

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
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10 of extract-agent-round-runner (design D11, D14 as amended 2026-11-25): the git-mode host
 * decoration holds its open round. A round that went pending passes that through to the host round
 * and is handed to a poll as it opened, its token never minted again; on a resuming instance a poll
 * continues the pending round the run's cell was restored with, over the cell's token and a host
 * environment of its own, and nothing else — a cell whose round is no pending one, or a round this
 * source already let go, is refused.
 */
class MidRoundPushRoundsContinuationSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    Path repo
    def host = new PassThroughRounds()
    def cell = new CurrentRound()

    def setup() {
        repo = initTaskWorkingRepo(tempDir)
    }

    private MidRoundPushRounds source() {
        new MidRoundPushRounds(host, new GitProcessRunner(), new VirtualClock(), cell)
    }

    private StageExecutor.Request request() {
        def stage = new StageDefinition(
                'implement', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                stage, new DirectoryWorkspace(repo), 0, [])
    }

    /** The cell as a resume restores it: a round open on {@code token}, its last snapshot {@code close}. */
    private RoundToken restored(RoundClose close) {
        def token = RoundToken.of(currentHead(repo))
        cell.open(token)
        cell.snapshotted(currentHead(repo), close)
        token
    }

    // FR10 (D11): going pending passes through to the host round, and the poll is handed that round
    def "a round that went pending is handed to the poll, the host round kept open"() {
        given:
        def source = source()
        def round = source.openRound(request())

        when:
        round.goPending()
        def polled = source.continueRound(request())

        then:
        host.round.wentPending
        !host.round.closed
        !host.round.discarded
        polled.is(round)
    }

    // FR10, FR11 (D14 as amended): a resuming instance's first poll continues the restored round, unminted
    def "a resuming source continues the pending round its cell was restored with, over the cell's token"() {
        given:
        def token = restored(RoundClose.PENDING)
        commit(repo, 'moved.txt', 'a tip a mint would read')
        def source = source()

        when:
        def polled = source.continueRound(request())

        then: 'a host environment of its own, and the cell still names the restored token'
        polled.environment().is(host.round.environment())
        cell.takeToken() == Optional.of(token)

        and: 'the poll holds it: a second poll is handed the same round, a close lets it go'
        source.continueRound(request()).is(polled)

        when:
        polled.closeRound(TerminalClose.VERIFIABLE)
        source.continueRound(request())

        then:
        host.round.closed
        thrown(NoOpenRoundException)
    }

    // FR10: only a pending round is continued; a restored interrupted verification is no round to poll
    def "a cell restored with a round that did not go pending is refused"() {
        given:
        restored(RoundClose.VERIFIABLE)

        when:
        source().continueRound(request())

        then:
        thrown(NoOpenRoundException)
        host.round == null
    }

    // FR10: a round this source let go is not adopted again, though the cell still names it
    def "a pending round this source already let go is not continued again"() {
        given:
        def source = source()
        def round = source.openRound(request())
        cell.snapshotted(currentHead(repo), RoundClose.PENDING)
        round.discard()

        when:
        source.continueRound(request())

        then:
        thrown(NoOpenRoundException)
        host.round.discarded
    }
}
