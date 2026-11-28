package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.RoundSubject
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.testfixtures.standin.StandIn

/**
 * Key rotation of a pending {@code transform} round on both media (FR12, reuse of FR10 and of D12 of
 * migrate-verify-to-executor-contract; design D11 of extract-agent-round-runner, task 6.6): a poll that
 * names another key lands a pending round commit carrying it through the persistence pair, pushed with its
 * register before the next poll, and the attempt stays open; the fixture is {@link PendingRoundFlowSpecBase}.
 */
class PendingRoundRotationFlowSpec extends PendingRoundFlowSpecBase {

    static final String ROTATED = 'job-8'

    // FR12 (reuse): the rotation of a round's key goes through the one persistence pair, the attempt open
    // Scenario: pipeline/stage-engine / A changed key of a pending round lands in a pending round commit
    def "FR12: in #mode mode, a poll that answers a new key lands a pending round commit carrying it, pushed before the next poll"() {
        given: 'the first poll names a new key, the second repeats it, the third completes'
        script([
            pendingAnswer(),
            pendingAnswer(ROTATED),
            pendingAnswer(ROTATED),
            completedAnswer()
        ])
        def opened = RoundToken.of(created)

        when:
        def outcome = mode == 'host' ? runHost() : runBox()

        then: 'two pending round commits, each registered and on origin when its write returned, the second on the first'
        writes*.op == ['pending', 'pending', 'round']
        writes[0..1].every { it.tip == it.register && it.origin == it.tip }
        parents(writes[1].tip as String) == [writes[0].tip]

        and: 'the second carries the new key in the round\'s one entry, and no attempt: the attempt stayed open'
        def first = stateAt(writes[0].tip as String)
        def rotated = stateAt(writes[1].tip as String)
        first.pendingPolls()*.key()*.value() == [KEY]
        rotated.pendingPolls().size() == 1
        with(rotated.pendingPolls()[0]) {
            subject() == PendingSubject.ROUND
            key().value() == ROTATED
            token() == opened
            since() == first.pendingPolls()[0].since()
        }
        rotated.attempts().isEmpty()

        and: 'the next polls asked for the new key, and the round closed on the rotated commit under its one token'
        StandIn.beside(link, 'heads').toFile().readLines()*.split(' ')*.getAt(1) == ['-', KEY, ROTATED, ROTATED]
        subjectsSince(created) == [
            RoundSubject.pendingSnapshot('build', 0, opened),
            RoundSubject.pending('build', 0, opened),
            RoundSubject.pending('build', 0, opened),
            RoundSubject.snapshot('build', 0, opened),
            'gnomish: round build#0'
        ]
        outcome instanceof TaskOutcome.Completed
        recordAtTip().roundToken() == opened

        where:
        mode << ['host', 'container']
    }
}
