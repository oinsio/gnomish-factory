package com.github.oinsio.gnomish.adapter.agent.claude

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.agent.JudgeVerdictExtractor
import com.github.oinsio.gnomish.adapter.agent.runner.DialectAnswer
import com.github.oinsio.gnomish.adapter.agent.runner.ParsedEvents
import com.github.oinsio.gnomish.adapter.agent.runner.RoundStatus
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The schema-enforced answer of the Claude dialect (FR3, FR4 of extract-agent-round-runner): a
 * {@code verdict} or {@code decision} round's structured output — or, when the CLI reported none,
 * the first JSON object of its final text, at WARN — judged against the schema the argv carried
 * and written to the answer file as the contract's answer; a reply that fails the schema is a
 * malformed answer, never a pass.
 *
 * <p>FR3, FR4 of extract-agent-round-runner.
 */
class ClaudeDialectAnswerSpec extends Specification {

    static final ObjectMapper JSON = new ObjectMapper()

    def dialect = new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com')

    @TempDir
    Path scratch

    Path answerFile

    def setup() {
        answerFile = scratch.resolve('answer.json')
    }

    def "FR4: a failing verdict's findings become error results of the SARIF verdict the answer file holds"() {
        when:
        def answer = dialect.answer(VERDICT, events(structured('{"passed":false,"findings":["no tests","typo"]}')), answerFile)

        then:
        answer instanceof DialectAnswer.Written
        def written = JSON.readTree(answerFile.toFile())
        written.kind.asText() == 'verdict'
        written.sarif.version.asText() == '2.1.0'
        written.sarif.runs[0].tool.driver.name.asText() == 'claude'
        written.sarif.runs[0].results*.level*.asText() == ['error', 'error']
        written.sarif.runs[0].results*.message*.text*.asText() == ['no tests', 'typo']
    }

    def "FR4: a passing verdict's findings are results of level none, and a failure without findings still fails"() {
        when:
        dialect.answer(VERDICT, events(structured(reply)), answerFile)

        then:
        def results = JSON.readTree(answerFile.toFile()).sarif.runs[0].results
        results*.level*.asText() == levels
        results*.message*.text*.asText() == texts

        where:
        reply || levels | texts
        '{"passed":true,"findings":["nit"]}' || ['none'] | ['nit']
        '{"passed":true}' || [] | []
        '{"passed":false}' || ['error'] | [
            JudgeVerdictExtractor.NO_FINDING
        ]
    }

    def "FR3: a decision's structured output is written as the select answer it chose"() {
        when:
        def answer = dialect.answer(DECISION, events(structured('{"chosen":"right","ranking":["right","left"],"rationale":"why"}')), answerFile)

        then:
        answer instanceof DialectAnswer.Written
        JSON.readTree(answerFile.toFile()) == JSON.readTree(
                '{"kind":"select","chosen":"right","ranking":["right","left"],"rationale":"why"}')
    }

    def "FR3: a decision without a ranking or rationale writes only its choice"() {
        when:
        dialect.answer(DECISION, events(structured('{"chosen":"left"}')), answerFile)

        then:
        JSON.readTree(answerFile.toFile()) == JSON.readTree('{"kind":"select","chosen":"left"}')
    }

    // Scenario: executor/claude-dialect / Malformed final output
    def "FR3, FR4: #what is a malformed answer and writes no answer file"() {
        when:
        def answer = dialect.answer(request, events(line), answerFile)

        then:
        answer instanceof DialectAnswer.Malformed
        (answer as DialectAnswer.Malformed).reason().forLog().contains(words)
        !Files.exists(answerFile)

        where:
        what | request | line || words
        'a verdict whose passed is no boolean' | VERDICT | structured('{"passed":"yes"}') || 'passed'
        'a verdict with a blank finding' | VERDICT | structured('{"passed":false,"findings":[" "]}') || 'findings'
        'a choice the request did not offer' | DECISION | structured('{"chosen":"up"}') || 'chosen'
        'a structured output that is no object' | VERDICT | structured('[true]') || 'not a JSON object'
        'a round whose structured-output retries ran out' | VERDICT | RETRIES_EXHAUSTED || 'structured-output retries'
    }

    def "FR3: a round whose structured-output retries ran out is completed, so its answer is judged malformed"() {
        expect:
        dialect.status(1, events(RETRIES_EXHAUSTED)) instanceof RoundStatus.Completed
    }

    def "FR3: a transform round's answer is the one the agent wrote itself"() {
        expect:
        dialect.answer(TRANSFORM, events(structured('{"passed":true}')), answerFile) instanceof DialectAnswer.NotWritten
        !Files.exists(answerFile)
    }

    def "FR3: an answer file that cannot be written is the factory's own failure, thrown"() {
        when:
        dialect.answer(VERDICT, events(structured('{"passed":true}')), scratch.resolve('missing/answer.json'))

        then:
        thrown(UncheckedIOException)
    }

    def "FR3: a schema the validator cannot use makes the reply malformed rather than accepted"() {
        when:
        def answer = ClaudeAnswer.judged(JSON.readTree('{"$ref":"https://example.com/s.json"}'), JSON.readTree('{}'))

        then:
        answer.isPresent()
    }

    static final ExecutorRequest VERDICT = request(Role.VERDICT, RoleInputs.verdict('is it built?'))
    static final ExecutorRequest DECISION = request(Role.DECISION, RoleInputs.decision('which way?', ['left', 'right']))
    static final ExecutorRequest TRANSFORM = new ExecutorRequest(Role.TRANSFORM, new Workspace('.'),
    new Policy.WorkspaceWrite(), RoleInputs.transform('build'), ExecutorContractJson.settingsTree([:]), Bounds.none(), [], null)

    static final String RETRIES_EXHAUSTED =
    '{"type":"result","subtype":"error_max_structured_output_retries","session_id":"s"}'

    private static ExecutorRequest request(Role role, RoleInputs inputs) {
        new ExecutorRequest(role, new Workspace('.'), new Policy.ReadOnly(), inputs,
                ExecutorContractJson.settingsTree([:]), Bounds.none(), [], null)
    }

    static String structured(String output) {
        '{"type":"result","subtype":"success","session_id":"s","result":"","structured_output":' + output + '}'
    }

    static String finalText(String text) {
        '{"type":"result","subtype":"success","session_id":"s","result":' + JSON.writeValueAsString(text) + '}'
    }

    static ParsedEvents<AgentEvent> events(ClaudeDialect dialect, String... lines) {
        List<ParsedEvents.Stamped<AgentEvent>> kept = []
        lines.each { line ->
            dialect.parse(line, new ParsedEvents<AgentEvent>(kept)).events().each {
                kept << new ParsedEvents.Stamped<AgentEvent>(Instant.EPOCH, it)
            }
        }
        new ParsedEvents<AgentEvent>(kept)
    }

    ParsedEvents<AgentEvent> events(String... lines) {
        events(dialect, lines)
    }
}
