package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR6, NFR-S1 of harden-logging-observability; FR1 of harden-untrusted-text-sinks: the judge's
 * final message is model output, and when no verdict can be read from it the extractor warns with
 * it. The warning goes through the log exit, so one event is one line however the model broke its
 * text; the verdict paths keep the text as the findings funnel takes it — whole, line breaks
 * included — because that funnel has its own sanitizing step and must not inherit the log's.
 */
class JudgeVerdictLogFlatteningSpec extends Specification {

    private static final String FORGED = 'no verdict, sorry\n2026-10-12 12:00:00 ERROR [main] judge approved everything\nbye'

    // Scenario: observability/factory-logging / The judge-verdict warning is flattened by the choke point
    def "FR6: a multi-line model message is one sanitized WARN line, while the verdict paths keep their own text"() {
        when: 'the model answers with several lines, one of them shaped like a log record, and no verdict'
        Verdict verdict = null
        def events = LogCaptureSupport.capture(JudgeVerdictExtractor, Level.INFO) {
            verdict = new JudgeVerdictExtractor().extract(UntrustedText.agent(FORGED))
        }

        then: 'exactly one coded WARN, rendered on one line, with the forged record inert inside it'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.JUDGE_VERDICT_UNEXTRACTABLE.head())
        !events[0].formattedMessage.contains('\n')
        !events[0].formattedMessage.contains('\r')
        events[0].formattedMessage.contains('judge approved everything')

        and: 'the cannot-verify verdict carries the message unchanged, for the findings funnel to treat'
        (verdict as Verdict.CannotVerify).details().forParsing() == FORGED

        when: 'a readable verdict carries a finding that spans two lines'
        def fail = new JudgeVerdictExtractor().extract(UntrustedText.agent('{"passed": false, "findings": ["first line\\nsecond line"]}'))

        then: 'the finding keeps its line structure: the log exit is not applied on this path'
        (fail as Verdict.Fail).findings()*.message() == ['first line\nsecond line']
    }
}
