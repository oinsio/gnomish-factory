package com.github.oinsio.gnomish.adapter.git

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
}
