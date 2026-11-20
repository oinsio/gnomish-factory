package com.github.oinsio.gnomish.adapter.engine

import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * FR2 of migrate-verify-to-executor-contract (design D21, "An in-place run keeps the key in
 * memory"): the production in-memory persistence keeps every pending write, in order, beside the
 * round writes and apart from them.
 */
class InMemoryPendingWriteSpec extends Specification {

    def "FR2: each pending write is kept in order, and none is a round write"() {
        given:
        def persistence = new InMemoryAttemptPersistence()
        def first = TaskState.atStageStart('work')
        def second = TaskState.atStageStart('review')

        when:
        persistence.persistPending('T-1', first)
        persistence.persistPending('T-1', second)

        then:
        persistence.pendingWrites() == [
            new InMemoryAttemptPersistence.PendingWrite('T-1', first),
            new InMemoryAttemptPersistence.PendingWrite('T-1', second)
        ]
        persistence.entries().isEmpty()
    }

    def "FR2: the kept pending writes cannot be changed through the view"() {
        when:
        new InMemoryAttemptPersistence().pendingWrites().add(new InMemoryAttemptPersistence.PendingWrite('T-1',
                TaskState.atStageStart('work')))

        then:
        thrown(UnsupportedOperationException)
    }
}
