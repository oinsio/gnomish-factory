package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.domain.engine.Verdict
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * FR8, NFR-R1, NFR-O2, D5: {@link JudgeVerdictExtractor} extracts a {@link
 * Verdict} tolerantly from the judge round's final message — fences stripped,
 * first JSON object taken. Covers: clean and fenced verdicts, leading/trailing
 * prose tolerance, and every "no verdict obtainable" path collapsing to {@link
 * Verdict.CannotVerify} with the raw message logged at WARN (never a silent
 * pass).
 */
class JudgeVerdictExtractorSpec extends Specification {

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(JudgeVerdictExtractor)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    def extractor = new JudgeVerdictExtractor()

    def "clean unfenced passing verdict yields Pass"() {
        given:
        def message = '{"passed": true}'

        when:
        def verdict = extractor.extract(message)

        then: 'FR8: passed:true maps to Pass'
        verdict == new Verdict.Pass()
    }

    def "fenced failing verdict with findings yields Fail with mapped findings"() {
        given: 'the "Fenced verdict accepted" scenario of spec.md'
        def message = '''
            Here is my assessment.
            ```json
            {"passed": false, "findings": ["missing test coverage", "typo in README"]}
            ```
            '''.stripIndent()

        when:
        def verdict = extractor.extract(message)

        then: 'FR8, D5: fenced JSON is parsed; findings mapped in order'
        verdict instanceof Verdict.Fail
        def fail = (Verdict.Fail) verdict
        fail.findings().size() == 2
        fail.findings()[0].message() == 'missing test coverage'
        fail.findings()[1].message() == 'typo in README'
    }

    def "plain (unlabeled) code fence is also stripped"() {
        given:
        def message = '''
            ```
            {"passed": true}
            ```
            '''.stripIndent()

        when:
        def verdict = extractor.extract(message)

        then:
        verdict == new Verdict.Pass()
    }

    def "leading and trailing prose around a valid JSON object still parses"() {
        given:
        def message = 'I reviewed the code carefully. {"passed": true} Thanks for reading.'

        when:
        def verdict = extractor.extract(message)

        then: 'D5: first JSON object is taken even with surrounding prose'
        verdict == new Verdict.Pass()
    }

    def "passed true with non-empty findings still yields Pass, findings discarded"() {
        given:
        def message = '{"passed": true, "findings": ["irrelevant"]}'

        when:
        def verdict = extractor.extract(message)

        then: 'Pass carries no findings field'
        verdict == new Verdict.Pass()
    }

    def "failing verdict with absent findings yields Fail with empty findings"() {
        given:
        def message = '{"passed": false}'

        when:
        def verdict = extractor.extract(message)

        then:
        verdict instanceof Verdict.Fail
        ((Verdict.Fail) verdict).findings().isEmpty()
    }

    def "garbage text with no JSON yields CannotVerify and logs raw message at WARN"() {
        given:
        def message = 'the agent rambled without ever producing a verdict'

        when:
        Verdict verdict = null
        def events = capture { verdict = extractor.extract(message) }

        then: 'NFR-R1: no verdict is never a silent pass'
        verdict instanceof Verdict.CannotVerify

        and: 'NFR-O2: raw message logged at WARN, with the no-JSON-found reason (not the malformed-JSON reason)'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.contains('no JSON verdict object found')
        events[0].formattedMessage.contains(message)
    }

    def "unterminated JSON object (never closes) yields CannotVerify, not a truncated parse"() {
        given: 'an opening brace with no matching close, distinguishing the no-object-found path from a malformed one'
        def message = '{"passed": true'

        when:
        Verdict verdict = null
        def events = capture { verdict = extractor.extract(message) }

        then:
        verdict instanceof Verdict.CannotVerify
        events[0].formattedMessage.contains('no JSON verdict object found')
    }

    def "nested braces inside the verdict object are balanced correctly"() {
        given: 'a nested object value between the opening and matching closing brace'
        def message = '{"passed": true, "meta": {"nested": "value"}}'

        when:
        def verdict = extractor.extract(message)

        then: 'D5: depth tracking finds the true matching outer brace, not the inner one'
        verdict == new Verdict.Pass()
    }

    def "JSON present but missing the passed field yields CannotVerify"() {
        given:
        def message = '{"findings": ["something"]}'

        when:
        def verdict = extractor.extract(message)

        then:
        verdict instanceof Verdict.CannotVerify
    }

    def "malformed JSON object yields CannotVerify and logs at WARN"() {
        given:
        def message = '{"passed": true, "findings": [oops}'

        when:
        Verdict verdict = null
        def events = capture { verdict = extractor.extract(message) }

        then:
        verdict instanceof Verdict.CannotVerify
        events.size() == 1
        events[0].level == Level.WARN
    }

    def "blank final message yields CannotVerify and logs at WARN"() {
        when:
        Verdict verdict = null
        def events = capture { verdict = extractor.extract('   ') }

        then: 'consistent with 6.3 empty-file handling'
        verdict instanceof Verdict.CannotVerify
        events.size() == 1
        events[0].level == Level.WARN
    }

    def "passed field that is not a boolean yields CannotVerify"() {
        given:
        def message = '{"passed": "yes"}'

        when:
        def verdict = extractor.extract(message)

        then:
        verdict instanceof Verdict.CannotVerify
    }

    def "valid Pass verdict does not log at WARN"() {
        given:
        def message = '{"passed": true}'

        when:
        def events = capture { extractor.extract(message) }

        then:
        events.isEmpty()
    }
}
