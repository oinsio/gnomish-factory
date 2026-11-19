package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import spock.lang.Specification

/**
 * FR15, FR17 of migrate-verify-to-executor-contract, design D16 ("A redirect target is an egress
 * target"; the admitted set of an in-process call): a manifest target is the law's request, judged
 * against the {@code http} built-in's own admitted set — the operator's {@code
 * factory.egress.in-process} scope — and never against another consumer's connection. The judgement
 * is built as its registry entry builds it ({@link HttpExternalCheckClient#guarded}) over the
 * production exchange; no feature opens a socket.
 */
class ManifestTargetAdmissionSpec extends Specification implements HttpCheckFixture {

    private static HttpExternalCheckClient provider(List<String> inProcess) {
        HttpExternalCheckClient.guarded(new OperatorEgress(inProcess), { name ->
            Optional.empty()
        } as SecretsProvider, HttpRunContext.none())
    }

    // FR17: the tracker connection's host is admitted for the tracker's own calls, not for a manifest
    // Scenario: verification/factory-egress-allowlist / A manifest target is never admitted by a connection
    def "a manifest target on the tracker connection's host is refused before any connection, naming the missing entry"() {
        when: 'the tracker connects to api.github.com, and the in-process scope does not name it'
        def status = provider(['sonar.example.com']).poll(check([url: 'https://api.github.com/repos/acme/widgets']), null)

        then:
        status instanceof PollStatus.CannotVerify
        def verdict = status as PollStatus.CannotVerify
        verdict.details().forLog() == 'missing allowlist entry'
        verdict.reason().forLog().contains('factory.egress.in-process')
    }
}
