package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.httpegress.EgressAllowlist
import com.github.oinsio.gnomish.httpegress.ExchangeLimitException
import com.github.oinsio.gnomish.httpegress.GuardedHttpCheckExchange
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.Provenance
import spock.lang.Specification

/**
 * FR10, FR11, NFR-S1, NFR-S2 of add-plugin-architecture: every way the check ends up with no
 * verdict at all. An unreachable, refused or interrupted exchange says nothing about the artifact,
 * and a credential or run variable this run cannot supply is never guessed. FR6, FR17 of
 * migrate-verify-to-executor-contract: through the built-in executor each is {@code
 * failed/infrastructure} naming the cause — no stage attempt is burned, no request is sent blind —
 * and the cause's details travel as the result's details, beside the reason (design D19).
 */
class HttpExecutorCannotVerifySpec extends Specification implements HttpCheckFixture {

    private static void assertInfrastructure(ExecutorResult result) {
        assert result.status() == Status.FAILED
        assert result.failureClass() == FailureClass.INFRASTRUCTURE
    }

    // FR10: an unreachable endpoint says nothing about the artifact — infrastructure, not quality,
    //     so no stage attempt is burned.
    def "an exchange that cannot complete is failed/infrastructure naming the endpoint and the cause"() {
        when:
        def result = run([url: URL], new ScriptedExchange(new IOException('connect timed out')))

        then:
        assertInfrastructure(result)
        result.reason().contains('could not reach ' + URL)
        result.details().contains('connect timed out')
        !result.reason().contains('connect timed out')

        and: 'the endpoint is the manifest\'s, not the tracker\'s'
        result.reason().provenance() == Provenance.MANIFEST
    }

    // FR17, D16 (the outcome classes): an unknown host is transport, never a configuration refusal —
    //     the guard resolves the granted name, the lookup fails, and nothing is sent or logged as refused
    // Scenario: verification/factory-egress-allowlist / An unresolvable host is an outage, not a refusal
    def "an unknown host is failed/infrastructure as transport, not an egress refusal"() {
        given: 'a host the in-process scope grants, which does not resolve'
        def platform = new ScriptedExchange(200, 'ok')
        def guard = new GuardedHttpCheckExchange(platform, new EgressAllowlist(['*.example.invalid'], { host ->
            throw new UnknownHostException(host)
        }, EgressAllowlist.IN_PROCESS_KEY, false))
        def logs = LogCaptureSupport.attach(HttpExternalCheckClient)

        when:
        def result = executor(guard, [:], HttpRunContext.none(), null).start(verdict([url: URL]))

        then:
        assertInfrastructure(result)
        result.reason().contains('could not reach ' + URL)
        result.details().contains(UnknownHostException.name)
        !result.reason().contains('refused')
        platform.lastRequest == null

        and: 'no egress refusal was logged: a lookup outage is not the operator\'s grant'
        logs.list.findAll { it.level.levelStr == 'WARN' }.isEmpty()

        cleanup:
        logs?.detach()
    }

    // FR10: the same classification for an interrupted wait, with the interrupt flag preserved.
    def "an interrupted exchange is failed/infrastructure and restores the interrupt flag"() {
        when:
        def result = run([url: URL], new ScriptedExchange(new InterruptedException('stopped')))

        then:
        assertInfrastructure(result)
        Thread.interrupted()
    }

    // NFR-S2: a refused target is reported as the egress rule that refused it — an infrastructure
    //     failure that burns no stage attempt, since a blocked target says nothing about the artifact.
    def "a target the egress allowlist refuses is failed/infrastructure naming the reason and the key"() {
        given:
        def refusal = new EgressRefusal(
                EgressRefusal.Reason.ADDRESS_CLASS, URL, 'resolves to 169.254.169.254', 'factory.egress.in-process')

        when:
        def result = run([url: URL], new ScriptedExchange(new EgressRefusedException(refusal)))

        then:
        assertInfrastructure(result)
        result.reason().contains('169.254.169.254')
        result.reason().contains('factory.egress.in-process')
        result.details().raw() == 'address class'

        and: 'the reason quotes the manifest target, so it keeps the manifest family'
        result.reason().provenance() == Provenance.MANIFEST
    }

    // FR15, design D16 of migrate-verify-to-executor-contract (the outcome classes): an answer that
    //     outruns the built-in's bound is infrastructure naming the limit, never advice to grant a host
    def "an exchange limit is failed/infrastructure naming the limit, not an egress refusal"() {
        given:
        def limit = new ExchangeLimitException(ExchangeLimitException.Limit.RESPONSE_SIZE, URL, 'over 1 MiB')

        when:
        def result = run([url: URL], new ScriptedExchange(limit))

        then:
        assertInfrastructure(result)
        result.details().contains('response size limit')
        !result.reason().contains('refused')
        !result.reason().contains(EgressAllowlist.IN_PROCESS_KEY)
    }

    // FR11, NFR-S1: an unresolvable credential is fail-closed — no request is sent, and the reason
    //     names the secret so an operator can fix it from the escalation report.
    def "an unresolvable credential is failed/infrastructure naming the secret, sending nothing"() {
        given:
        def exchange = new ScriptedExchange(200, 'ok')

        when:
        def result = run([url: URL, auth: [credential: 'GNOMISH_SONAR_TOKEN']], exchange)

        then:
        assertInfrastructure(result)
        result.reason().contains('GNOMISH_SONAR_TOKEN')
        exchange.lastRequest == null
    }

    // NFR-S2: fail closed — a run that cannot supply a value never guesses one.
    def "a reference this run cannot supply is failed/infrastructure naming the variable"() {
        given:
        def exchange = new ScriptedExchange(200, 'ok')

        when:
        def result = run([url: 'https://ci.example.invalid/${attempt.commit}'], exchange)

        then:
        assertInfrastructure(result)
        result.reason().contains('attempt.commit')
        exchange.lastRequest == null
    }

    // NFR-S2: a round not yet closed by a snapshot carries no attempt commit either — same fail-closed
    //     answer as a workspace that never had one, never an empty substitution.
    def "a workspace whose round recorded no attempt commit fails the check closed"() {
        given:
        def exchange = new ScriptedExchange(200, 'ok')
        def workspace = new RecordedAttemptCommitWorkspace(new CurrentRound())

        when:
        def result = executor(exchange, [:], HttpRunContext.none(), workspace)
        .start(verdict([url: 'https://ci.example.invalid/${attempt.commit}']))

        then:
        assertInfrastructure(result)
        result.reason().contains('attempt.commit')
        exchange.lastRequest == null
    }
}
