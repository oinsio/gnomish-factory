package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.PollStatus
import spock.lang.Specification

/**
 * FR10, FR11, NFR-S1, NFR-S2 of add-plugin-architecture: every way the provider ends up with no
 * verdict at all. An unreachable, refused or interrupted exchange says nothing about the artifact,
 * and a credential or run variable this run cannot supply is never guessed — all of them are a
 * CannotVerify naming the cause, so no stage attempt is burned and no request is sent blind.
 */
class HttpExternalCheckClientCannotVerifySpec extends Specification implements HttpCheckFixture {

    // FR10: an unreachable endpoint says nothing about the artifact — infrastructure, not quality,
    //     so no stage attempt is burned.
    def "an exchange that cannot complete is a CannotVerify naming the endpoint"() {
        when:
        def status = poll([url: URL], new ScriptedExchange(new IOException('connect timed out')))

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains(URL)
        status.details().contains('connect timed out')
    }

    // FR10: the same classification for an interrupted wait, with the interrupt flag preserved.
    def "an interrupted exchange is a CannotVerify and restores the interrupt flag"() {
        when:
        def status = poll([url: URL], new ScriptedExchange(new InterruptedException('stopped')))

        then:
        status instanceof PollStatus.CannotVerify
        Thread.interrupted()
    }

    // NFR-S2: a refused target is reported as the egress rule that refused it — an infrastructure
    //     failure that burns no stage attempt, since a blocked target says nothing about the artifact.
    def "a target the egress allowlist refuses is a CannotVerify naming the reason"() {
        given:
        def refusal = new EgressRefusal(EgressRefusal.Reason.ADDRESS_CLASS, URL, 'resolves to 169.254.169.254')
        def exchange = new ScriptedExchange(new EgressRefusedException(refusal))

        when:
        def status = poll([url: URL], exchange)

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains('169.254.169.254')
        status.details() == 'address class'
    }

    // FR11, NFR-S1: an unresolvable credential is fail-closed — no request is sent, and the reason
    //     names the secret so an operator can fix it from the escalation report.
    def "an unresolvable credential is a CannotVerify naming the secret, sending nothing"() {
        given:
        def exchange = new ScriptedExchange(200, 'ok')

        when:
        def status = poll([url: URL, auth: [credential: 'GNOMISH_SONAR_TOKEN']], exchange, [:])

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains('GNOMISH_SONAR_TOKEN')
        exchange.lastRequest == null
    }

    // NFR-S2: fail closed — a run that cannot supply a value never guesses one.
    def "a reference this run cannot supply is a CannotVerify naming the variable"() {
        given:
        def exchange = new ScriptedExchange(200, 'ok')

        when:
        def status = poll([url: 'https://ci.example.invalid/${attempt.commit}'], exchange)

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains('attempt.commit')
        exchange.lastRequest == null
    }

    // NFR-S2: a round not yet closed by a snapshot carries no attempt commit either — same fail-closed
    //     answer as a workspace that never had one, never an empty substitution.
    def "a workspace whose round recorded no attempt commit fails the check closed"() {
        given:
        def exchange = new ScriptedExchange(200, 'ok')
        def workspace = new RecordedAttemptCommitWorkspace(new AttemptCommitRef())

        when:
        def status = new HttpExternalCheckClient(exchange, providing([:]))
        .poll(check([url: 'https://ci.example.invalid/${attempt.commit}']), workspace)

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains('attempt.commit')
        exchange.lastRequest == null
    }
}
