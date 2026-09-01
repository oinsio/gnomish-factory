package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * FR5 of harden-logging-observability: an http check refused by the egress guard never reaches a
 * server, so nothing outside the factory records that it happened. The refusal is logged once,
 * where it becomes a verdict — naming the check, the rule that refused it, and the target.
 *
 * <p>Both refusal classes the guard produces are covered: a target no allowlist entry permits, and
 * a redirect chain that outran its bound. The second carries a location a remote server chose, so
 * the line is also asserted to stay one line (FR6).
 */
class HttpCheckRefusalLoggingSpec extends Specification implements HttpCheckFixture {

    /** The escape a forged redirect target would open a colour sequence with. */
    private static final String ESC = Character.toString(0x1B)

    LogCaptureSupport logs = LogCaptureSupport.attach(HttpExternalCheckClient)

    def cleanup() {
        logs.detach()
    }

    private String refusalLine() {
        def warnings = logs.list.findAll { it.level.levelStr == 'WARN' }
        assert warnings.size() == 1
        warnings[0].formattedMessage
    }

    def "FR5: a target the allowlist refuses is logged once, naming the check and the rule"() {
        given:
        def refusal = new EgressRefusal(
                EgressRefusal.Reason.NOT_ALLOWLISTED, URL, 'no allowlist entry for ci.example.invalid')

        when:
        poll([url: URL], new ScriptedExchange(new EgressRefusedException(refusal)))

        then:
        def line = refusalLine()
        line.contains("http check 'quality-gate'")
        line.contains('missing allowlist entry')
        line.contains(URL)
    }

    def "FR5, FR6: a redirect chain past its bound is logged as one line naming the rule"() {
        given: 'a refusal whose target is the location a remote server chose, newlines and all'
        def forged = 'https://evil.example.invalid/' + ESC + '[31m?x=1\nWARN forged line'
        def refusal = new EgressRefusal(EgressRefusal.Reason.REDIRECT_LIMIT, forged, 'more than 3 redirects followed')

        when:
        poll([url: URL], new ScriptedExchange(new EgressRefusedException(refusal)))

        then:
        def line = refusalLine()
        line.contains('redirect limit')

        and: 'the remote-chosen target cannot forge a second record or carry an escape sequence'
        !line.contains('\n')
        !line.contains(ESC)
    }
}
