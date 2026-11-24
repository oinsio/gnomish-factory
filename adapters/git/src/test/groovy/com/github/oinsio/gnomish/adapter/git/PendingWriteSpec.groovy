package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * FR2, FR20 of migrate-verify-to-executor-contract (design D21): one {@code persistPending} call as
 * both persistence ends read it — the stage from the position, the round and token from the list's
 * last entry — and the port contract it refuses before anything is written.
 */
class PendingWriteSpec extends Specification {

    static final RoundToken TOKEN = RoundToken.of('0a1b2c')

    private static PendingPollRecord entry(int check, int attempt) {
        new PendingPollRecord(new PendingSubject.Check(new CheckRef(check, UntrustedText.factory('ci'))), attempt, TOKEN, new PendingKey('run-' + check),
                Instant.EPOCH)
    }

    def "FR20: the write's stage is the position's, its key and subject the last entry's"() {
        when:
        def write = PendingWrite.of('T-1', TaskState.atStageStart('review').withPendingPolls([entry(0, 2), entry(3, 2)]))

        then:
        write.stage() == 'review'
        write.last() == entry(3, 2)
        write.key() == new AttemptKey('T-1', 'review', 2)
        write.subject() == 'gnomish: pending review#2 0a1b2c'
    }

    def "FR20: a state #breach is refused before anything is written"() {
        when:
        PendingWrite.of('T-1', state)

        then:
        def refused = thrown(GitPersistFailedException)
        refused.message.contains(evidence)

        where:
        breach | state || evidence
        'with no pending entry' | TaskState.atStageStart('review') || 'the state carries no pending entry'
        'not positioned at a stage' | new TaskState(new Position.AwaitingApproval('review'), 0, [], TaskState.atStageStart('review').totals(),
        TaskState.atStageStart('review').executorVersions()).withPendingPolls([entry(0, 1)]) || 'the state is not positioned at a stage'
    }

    def "FR20: a round with no snapshot is refused naming the stage and round"() {
        given:
        def write = PendingWrite.of('T-1', TaskState.atStageStart('review').withPendingPolls([entry(0, 4)]))

        expect:
        write.noSnapshot().message.contains('no registered snapshot')
        write.noSnapshot().message.contains('review')
    }

    private static PendingPollRecord roundEntry(int attempt) {
        new PendingPollRecord(PendingSubject.ROUND, attempt, TOKEN, new PendingKey('job-' + attempt), Instant.EPOCH)
    }

    private static ClosedRound closed(RoundClose close) {
        new ClosedRound(TOKEN, 'snap', 'reg', close)
    }

    // FR10 of extract-agent-round-runner (design D7, D11): the pair's one subject rule — a check's
    // entry lands above a verifiable snapshot, the round's own above its pending-round snapshot
    def "FR10: a #subject entry lands on a #close snapshot"() {
        given:
        def write = PendingWrite.of('T-1', TaskState.atStageStart('review').withPendingPolls([last]))

        expect:
        write.landsOn(Optional.of(closed(close))) == closed(close)
        write.subject() == 'gnomish: pending review#2 0a1b2c'

        where:
        subject | last | close
        'check' | entry(0, 2) | RoundClose.VERIFIABLE
        'round' | roundEntry(2) | RoundClose.PENDING
    }

    // FR10 (design D11): an entry above a snapshot of another close would let a pickup verify a
    // pending round, or poll a finished one — refused before anything is written
    def "FR10: a #subject entry above a #close snapshot is refused, naming both"() {
        given:
        def write = PendingWrite.of('T-1', TaskState.atStageStart('review').withPendingPolls([last]))

        when:
        write.landsOn(Optional.of(closed(close)))

        then:
        def refused = thrown(GitPersistFailedException)
        refused.message.contains(named)
        refused.message.contains(close.name())

        where:
        subject | last | close || named
        'check' | entry(0, 2) | RoundClose.PENDING || 'check 0'
        'check' | entry(0, 2) | RoundClose.UNVERIFIABLE || 'check 0'
        'round' | roundEntry(2) | RoundClose.VERIFIABLE || 'the round'
        'round' | roundEntry(2) | RoundClose.UNVERIFIABLE || 'the round'
    }

    def "FR20: a round with no snapshot to land on is refused"() {
        given:
        def write = PendingWrite.of('T-1', TaskState.atStageStart('review').withPendingPolls([roundEntry(4)]))

        when:
        write.landsOn(Optional.empty())

        then:
        def refused = thrown(GitPersistFailedException)
        refused.message.contains('no registered snapshot')
    }
}
