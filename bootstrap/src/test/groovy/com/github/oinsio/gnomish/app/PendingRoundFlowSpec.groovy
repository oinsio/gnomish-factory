package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.RoundSubject
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.testfixtures.standin.StandIn

/**
 * A pending {@code transform} round on both media, polled with the recorded key (FR10, NFR-R4 of
 * extract-agent-round-runner; design D11 as amended 2026-11-25, task 6.4a); the fixture is {@link
 * PendingRoundFlowSpecBase}.
 */
class PendingRoundFlowSpec extends PendingRoundFlowSpecBase {

    // Scenario: pipeline/stage-engine / A poll that answers pending writes nothing
    def "FR10: in #mode mode, a poll of a pending round that answers pending writes nothing and runs in the start's environment"() {
        when:
        def outcome = mode == 'host' ? runHost() : runBox()

        then: 'one pending round commit, written once and registered with the branch, then the round\'s close'
        writes*.op == ['pending', 'round']
        def pendingCommit = writes[0].tip
        writes[0].register == pendingCommit

        and: 'the polls that answered pending moved neither the branch nor its register: the close registered its snapshot right on the pending round commit'
        writes[1].tip == writes[1].register
        gitOutput(clone, 'rev-list', '--parents', '-n', '1', writes[1].register as String).split(' ').drop(1).toList() == [pendingCommit]

        and: 'the close was recorded: the round completed'
        outcome instanceof TaskOutcome.Completed

        and: 'every request ran in the environment the start ran in'
        def requests = StandIn.beside(link, 'heads').toFile().readLines()*.split(' ')*.toList()
        requests*.take(2)*.join(' ') == ['start -'] + ['poll ' + KEY] * 3
        requests*.getAt(3).toSet().size() == 1

        where:
        mode << ['host', 'container']
    }

    // Scenario: pipeline/stage-engine / Every commit of a pending round names one token
    // Scenario: git/git-task-persistence / Only the start of a round registers a pending-round snapshot
    def "FR10: in #mode mode, every commit of a round polled twice and closed by a completed poll names the token the round opened with"() {
        given:
        def token = RoundToken.of(created)

        when:
        def outcome = mode == 'host' ? runHost() : runBox()

        then: 'the branch holds exactly the round\'s four commits, each subject naming its one token'
        subjectsSince(created) == [
            RoundSubject.pendingSnapshot('build', 0, token),
            RoundSubject.pending('build', 0, token),
            RoundSubject.snapshot('build', 0, token),
            'gnomish: round build#0'
        ]

        and: 'the round closed, its verify chain ran and passed with no boundary violation, and its record names the token'
        outcome instanceof TaskOutcome.Completed
        def record = recordAtTip()
        record.result() == AttemptRecord.Result.PASSED
        record.checkResults().size() == 1
        record.roundToken() == token
        gitOutput(clone, 'rev-parse', REGISTER) == tip()

        where:
        mode << ['host', 'container']
    }
}
