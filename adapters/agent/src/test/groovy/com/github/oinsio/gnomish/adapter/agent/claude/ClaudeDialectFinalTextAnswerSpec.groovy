package com.github.oinsio.gnomish.adapter.agent.claude

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.agent.runner.DialectAnswer
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

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

    @TempDir
    Path scratch

    Path answerFile

    def setup() {
        answerFile = scratch.resolve('answer.json')
    }

    def "FR3: with no structured output the answer is read from the final text, at WARN"() {
        when:
        DialectAnswer answer = null
        def logged = LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
            answer = dialect.answer(ClaudeDialectAnswerSpec.VERDICT, ClaudeDialectAnswerSpec.events(dialect, ClaudeDialectAnswerSpec.finalText('Done.\n```json\n{"passed":false,"findings":["x"]}\n```')), answerFile)
        }

        then:
        answer instanceof DialectAnswer.Written
        JSON.readTree(answerFile.toFile()).sarif.runs[0].results*.message*.text*.asText() == ['x']
        logged.size() == 1
        logged[0].level == Level.WARN
        logged[0].formattedMessage.startsWith(OperatorEvent.AGENT_ANSWER_FROM_FINAL_TEXT.head())
        logged[0].formattedMessage.contains('verdict')
    }

    def "FR3: a final text #what is a malformed answer"() {
        when:
        DialectAnswer answer = null
        LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
            answer = dialect.answer(ClaudeDialectAnswerSpec.VERDICT, ClaudeDialectAnswerSpec.events(dialect, ClaudeDialectAnswerSpec.finalText(text)), answerFile)
        }

        then:
        (answer as DialectAnswer.Malformed).reason().forLog().contains(words)

        where:
        what | text || words
        'with no JSON object' | 'all good' || 'no JSON object'
        'whose object is not JSON' | '{"passed": tru}' || 'not valid JSON'
    }

    def "FR3: a JSON null structured output is no structured output, so the final text is read"() {
        when:
        DialectAnswer answer = null
        def logged = LogCaptureSupport.capture(ClaudeAnswer, Level.INFO) {
            answer = dialect.answer(ClaudeDialectAnswerSpec.VERDICT, ClaudeDialectAnswerSpec.events(dialect, '{"type":"result","subtype":"success","session_id":"s",' +
            '"result":"{\\"passed\\":true}","structured_output":null}'), answerFile)
        }

        then:
        answer instanceof DialectAnswer.Written
        logged*.formattedMessage.every {
            it.startsWith(OperatorEvent.AGENT_ANSWER_FROM_FINAL_TEXT.head())
        }
        logged.size() == 1
    }
}
