package com.github.oinsio.gnomish.adapter.git

import static com.github.oinsio.gnomish.adapter.git.PendingStateLagSpec.CHAIN
import static com.github.oinsio.gnomish.adapter.git.PendingStateLagSpec.LAGGED
import static com.github.oinsio.gnomish.adapter.git.PendingStateLagSpec.P2
import static com.github.oinsio.gnomish.adapter.git.PendingStateLagSpec.SNAP
import static com.github.oinsio.gnomish.adapter.git.PendingStateLagSpec.WORKTREE

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * FR10 of extract-agent-round-runner (design D11 as amended 2026-11-25, finding F1 of task 6.4d):
 * the settle a persistence runs right after a pending commit landed, on the git port — the working
 * copy's {@code state.json} lag restored for a pending round commit only, never for a check's, whose
 * run is still alive there; and the throwing form both callers use. The real media are
 * {@code HostPendingRoundCommitSpec}'s, {@code InBoxPendingRoundCommitSpec}'s and
 * {@code HostPendingCommitSpec}'s.
 */
class PendingStateLagLandingSpec extends Specification {

    private static PendingWrite landed(PendingSubject subject) {
        new PendingWrite('T-1', 'implement', new PendingPollRecord(subject, 3, RoundToken.of('0a1b2c'), new PendingKey('job'), Instant.EPOCH))
    }

    // FR10 (design D11 as amended 2026-11-25, F1 of task 6.4d): a pending round commit's landing settles
    //     the working copy, so an executor commit during a poll carries the factory's own state.json
    def "FR10: the landing of a pending round commit restores the lag from the landed commit"() {
        given:
        def git = PendingStateLagSpec.chain(PendingStateLagSpec.worktreeHolding(LAGGED, LAGGED))
                .answer(['restore'], 0)

        when:
        new PendingStateLag(WorkingCopyGit.host(git, WORKTREE)).afterLanding(landed(PendingSubject.ROUND), CHAIN)

        then:
        git.calls(['restore'])*.command()*.get(1) == ['--source=' + P2]
    }

    // FR10 (design D11): a pending check's run is still alive in the working copy, so its landing touches nothing
    def "FR10: the landing of a pending check's commit reads and restores nothing in the working copy"() {
        given:
        def git = PendingStateLagSpec.chain(PendingStateLagSpec.worktreeHolding(LAGGED, LAGGED))
                .answer(['restore'], 0)

        when:
        new PendingStateLag(WorkingCopyGit.host(git, WORKTREE))
                .afterLanding(landed(new PendingSubject.Check(new CheckRef(0, UntrustedText.factory('ci')))), CHAIN)

        then:
        git.calls().isEmpty()
    }

    def "FR10: a lag the landing could not restore is the round's infrastructure failure, naming the round"() {
        given:
        def git = PendingStateLagSpec.chain(PendingStateLagSpec.worktreeHolding(LAGGED, LAGGED))
                .answer(['restore'], 1, '', 'fatal: locked')

        when:
        new PendingStateLag(WorkingCopyGit.host(git, WORKTREE)).afterLanding(landed(PendingSubject.ROUND), CHAIN)

        then:
        def e = thrown(GitPersistFailedException)
        e.message.contains('T-1') && e.message.contains('implement') && e.message.contains('3')
        e.message.contains('pending-commit lag') && e.message.contains('locked')
    }

    def "FR20: a settle with nothing owed throws nothing"() {
        given:
        def git = new ScriptedGit()

        when:
        new PendingStateLag(WorkingCopyGit.host(git, WORKTREE)).settled(new AttemptKey('T-1', 'implement', 3), [SNAP])

        then:
        noExceptionThrown()
        git.calls().isEmpty()
    }
}
