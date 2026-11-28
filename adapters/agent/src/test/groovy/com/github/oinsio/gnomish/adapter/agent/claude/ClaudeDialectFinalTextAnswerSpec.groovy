package com.github.oinsio.gnomish.adapter.agent.claude

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.agent.runner.DialectAnswer
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The Claude dialect's final-text fallback (FR3 of extract-agent-round-runner): a {@code verdict} or
 * {@code decision} round whose result event carries no structured output — a CLI that ignored
 * {@code --json-schema} — has its answer read from the one JSON object of its final text, at WARN,
 * and judged against the same schema; a final text with no usable object is a malformed answer.
 *
 * <p>FR3, FR4 of extract-agent-round-runner.
 */
class ClaudeDialectFinalTextAnswerSpec extends Specification {

    static final ObjectMapper JSON = new ObjectMapper()

    def dialect = new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com')

    def "FR3: with no structured output the answer is read from the final text, at WARN"() {
        when:
        DialectAnswer answer = null
        def logged = LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
            answer = dialect.answer(ClaudeDialectAnswerSpec.VERDICT, ClaudeDialectAnswerSpec.events(dialect, ClaudeDialectAnswerSpec.finalText('Done.\n```json\n{"passed":false,"findings":["x"]}\n```')))
        }

        then:
        answer instanceof DialectAnswer.Replied
        (answer as DialectAnswer.Replied).document().sarif.runs[0].results*.message*.text*.asText() == ['x']
        logged.size() == 1
        logged[0].level == Level.WARN
        logged[0].formattedMessage.startsWith(OperatorEvent.AGENT_ANSWER_FROM_FINAL_TEXT.head())
        logged[0].formattedMessage.contains('verdict')
    }

    def "FR3: a final text #what is a malformed answer"() {
        when:
        DialectAnswer answer = null
        LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
            answer = dialect.answer(ClaudeDialectAnswerSpec.VERDICT, ClaudeDialectAnswerSpec.events(dialect, ClaudeDialectAnswerSpec.finalText(text)))
        }

        then:
        (answer as DialectAnswer.Malformed).reason().forLog().contains(words)

        and: 'the reason quotes the final text it could not read'
        (answer as DialectAnswer.Malformed).reason().forLog().contains('the final text was: ' + text)

        where:
        what | text || words
        'with no JSON object' | 'all good' || 'no JSON object'
        'whose object is not JSON' | '{"passed": tru}' || 'not valid JSON'
        'whose object never closes' | '{"passed": true' || 'no JSON object'
    }

    // D5 of add-agent-executor: the final text is free text, so the answer is its first JSON object
    //     — a fence stripped, prose around it ignored, braces nested inside it balanced, and a later
    //     object never overriding it (moved from the judge-round extractor's specs, task 4.3)
    def "FR3: the answer is the first JSON object of a final text with #shape"() {
        when:
        DialectAnswer answer = null
        LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
            answer = dialect.answer(ClaudeDialectAnswerSpec.VERDICT, ClaudeDialectAnswerSpec.events(dialect, ClaudeDialectAnswerSpec.finalText(text)))
        }

        then:
        answer instanceof DialectAnswer.Replied
        def results = (answer as DialectAnswer.Replied).document().sarif.runs[0].results
        results*.message*.text*.asText() == messages
        results*.level*.asText().every {
            it == (messages.isEmpty() ? 'none' : 'error')
        }

        where:
        shape | text || messages
        'nothing around it' | '{"passed": true, "findings": []}' || []
        'a plain code fence' | '```\n{"passed": true, "findings": []}\n```' || []
        'prose around it' | 'I reviewed the code carefully. {"passed": true, "findings": []} Thanks for reading.' || []
        'nested braces' | '{"passed": false, "findings": ["a map {k: {v}} is unsorted"]}' || ['a map {k: {v}} is unsorted']
        'prose around a failing object' | 'I checked it. {"passed": false, "findings": ["no tests"]} Done.' || ['no tests']
        'a pass, then a fail' | '{"passed": true, "findings": []} Actually, on reflection: {"passed": false, "findings": ["x"]}' || []
        'a fail, then a pass' | 'Draft: {"passed": false, "findings": ["x"]} Final: {"passed": true, "findings": []}' || ['x']
    }

    def "FR3: a JSON null structured output is no structured output, so the final text is read"() {
        when:
        DialectAnswer answer = null
        def logged = LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
            answer = dialect.answer(ClaudeDialectAnswerSpec.VERDICT, ClaudeDialectAnswerSpec.events(dialect, '{"type":"result","subtype":"success","session_id":"s",' +
            '"result":"{\\"passed\\":true,\\"findings\\":[]}","structured_output":null}'))
        }

        then:
        answer instanceof DialectAnswer.Replied
        logged*.formattedMessage.every {
            it.startsWith(OperatorEvent.AGENT_ANSWER_FROM_FINAL_TEXT.head())
        }
        logged.size() == 1
    }
}
