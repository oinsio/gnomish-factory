package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading
import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.engine.RoundToken
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR19 of migrate-verify-to-executor-contract (design D22): the one owner of "no salvage over an
 * interrupted verification", over the two media's ports. A register naming a snapshot or a pending
 * commit is never salvaged over: the host discards, with or without {@code --discard-work}; the box
 * keeps its leftovers out of every commit. Any other register keeps today's salvage or discard.
 * The real-medium features are {@code HostInterruptedVerificationResumeSpec} and
 * {@code InterruptedVerificationRevocationSpec} in {@code :bootstrap}.
 */
class NoSalvageOverVerificationSpec extends Specification {

    static final Path WORKTREE = Path.of('/tmp/worktree')

    static final PendingVerification SNAPSHOT =
    new PendingVerification('snap', 'build', 0, RoundToken.of('0a1b2c'), new AnswerFileReading.Absent(), 'pending', ['pending', 'snap'])

    WorktreeSalvager salvager = Mock()

    TaskWorktreeGit worktrees(Optional<PendingVerification> registered) {
        Stub(TaskWorktreeGit) {
            interruptedVerification(WORKTREE, 'PROJ-1') >> registered
            salvage(WORKTREE) >> salvager
        }
    }

    def "FR19: a host register naming an interrupted verification is discarded over, #flag, and handed on"() {
        when:
        def pending = NoSalvageOverVerification.onHost(worktrees(Optional.of(SNAPSHOT)), WORKTREE, 'PROJ-1', discardWork)

        then:
        1 * salvager.discard()
        0 * salvager.salvage(_)
        pending.is(SNAPSHOT)

        where:
        flag | discardWork
        'without --discard-work' | false
        'under --discard-work' | true
    }

    def "FR19: a host register naming no interrupted verification is salvaged by default"() {
        when:
        def pending = NoSalvageOverVerification.onHost(worktrees(Optional.empty()), WORKTREE, 'PROJ-1', false)

        then:
        1 * salvager.salvage('PROJ-1')
        0 * salvager.discard()
        pending == null
    }

    def "FR19: a host register naming no interrupted verification is discarded under --discard-work"() {
        when:
        def pending = NoSalvageOverVerification.onHost(worktrees(Optional.empty()), WORKTREE, 'PROJ-1', true)

        then:
        1 * salvager.discard()
        0 * salvager.salvage(_)
        pending == null
    }

    def "FR19: the box salvages only when the register names no interrupted verification"() {
        given:
        def support = Mock(SandboxRunSupport)

        when:
        def pending = NoSalvageOverVerification.inBox(support, registered, 'PROJ-1')

        then:
        salvages * support.salvageLeftovers('PROJ-1')
        pending == registered.orElse(null)

        where:
        registered || salvages
        Optional.empty() || 1
        Optional.of(SNAPSHOT) || 0
    }

    def "FR19: a revocation stop in the box reads the register and says whether it kept the leftovers out"() {
        given:
        def support = Mock(SandboxRunSupport)

        when:
        def kept = NoSalvageOverVerification.keptOutInBox(support, 'PROJ-1')

        then:
        1 * support.pendingVerification() >> registered
        salvages * support.salvageLeftovers('PROJ-1')
        kept == expected

        where:
        registered || salvages | expected
        Optional.empty() || 1 | false
        Optional.of(SNAPSHOT) || 0 | true
    }
}
