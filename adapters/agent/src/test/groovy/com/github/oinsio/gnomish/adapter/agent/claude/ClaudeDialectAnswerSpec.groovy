package com.github.oinsio.gnomish.adapter.agent.claude

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.agent.JudgeVerdictExtractor
import com.github.oinsio.gnomish.adapter.agent.runner.AnswerForm
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
import java.time.Instant
import spock.lang.Specification

/**
 * The schema-enforced answer of the Claude dialect (FR3, FR4 of extract-agent-round-runner): a
 * {@code verdict} or {@code decision} round's structured output — or, when the CLI reported none,
 * the first JSON object of its final text, at WARN — judged against the schema the argv carried
 * and stated as the contract's answer the runner writes; a reply that fails the schema is a
 * malformed answer, never a pass.
 *
 * <p>FR3, FR4, FR19 of extract-agent-round-runner.
 */
class ClaudeDialectAnswerSpec extends Specification {

    static final ObjectMapper JSON = new ObjectMapper()

    def dialect = new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com')

    def "FR4: a failing verdict's findings become error results of the SARIF verdict the answer file holds"() {
        when:
        def answer = dialect.answer(VERDICT, events(structured('{"passed":false,"findings":["no tests","typo"]}')))

        then:
        answer instanceof DialectAnswer.Replied
        def written = (answer as DialectAnswer.Replied).document()
        written.kind.asText() == 'verdict'
        written.sarif.version.asText() == '2.1.0'
        written.sarif.runs[0].tool.driver.name.asText() == 'claude'
        written.sarif.runs[0].results*.level*.asText() == ['error', 'error']
        written.sarif.runs[0].results*.message*.text*.asText() == ['no tests', 'typo']
    }

    def "FR4: a passing verdict's findings are results of level none, and a failure without findings still fails"() {
        when:
        def answer = dialect.answer(VERDICT, events(structured(reply)))

        then:
        def results = (answer as DialectAnswer.Replied).document().sarif.runs[0].results
        results*.level*.asText() == levels
        results*.message*.text*.asText() == texts

        where:
        reply || levels | texts
        '{"passed":true,"findings":["nit"]}' || ['none'] | ['nit']
        '{"passed":true,"findings":[]}' || [] | []
        '{"passed":false,"findings":[]}' || ['error'] | [
            JudgeVerdictExtractor.NO_FINDING
        ]
    }

    def "FR3: a decision's structured output is written as the select answer it chose"() {
        when:
        def answer = dialect.answer(DECISION, events(structured('{"chosen":"right","ranking":["right","left"],"rationale":"why"}')))

        then:
        answer instanceof DialectAnswer.Replied
        (answer as DialectAnswer.Replied).document() == JSON.readTree(
                '{"kind":"select","chosen":"right","ranking":["right","left"],"rationale":"why"}')
    }

    def "FR3: a decision with an empty ranking and a blank rationale states only its choice"() {
        when:
        def answer = dialect.answer(DECISION, events(structured('{"chosen":"left","ranking":[],"rationale":" "}')))

        then:
        (answer as DialectAnswer.Replied).document() == JSON.readTree('{"kind":"select","chosen":"left"}')
    }

    // Scenario: executor/claude-dialect / Malformed final output
    def "FR3, FR4: #what is a malformed answer"() {
        when:
        def answer = dialect.answer(request, events(line))

        then:
        answer instanceof DialectAnswer.Malformed
        (answer as DialectAnswer.Malformed).reason().forLog().contains(words)

        where:
        what | request | line || words
        'a verdict whose passed is no boolean' | VERDICT | structured('{"passed":"yes","findings":[]}') || 'passed'
        'a verdict with a blank finding' | VERDICT | structured('{"passed":false,"findings":[" "]}') || 'findings'
        'a verdict without its findings' | VERDICT | structured('{"passed":true}') || 'findings'
        'a verdict with a field the schema does not name' | VERDICT | structured('{"passed":true,"findings":[],"note":"x"}') || 'note'
        'a choice the request did not offer' | DECISION | structured('{"chosen":"up","ranking":[],"rationale":""}') || 'chosen'
        'a transform reply with an unknown outcome' | TRANSFORM | structured('{"outcome":"maybe","question":"","options":[]}') || 'outcome'
        'a structured output that is no object' | VERDICT | structured('[true]') || 'not a JSON object'
        'a round whose structured-output retries ran out' | VERDICT | RETRIES_EXHAUSTED || 'structured-output retries'
    }

    def "FR3: a round whose structured-output retries ran out is completed, so its answer is judged malformed"() {
        expect:
        dialect.status(1, events(RETRIES_EXHAUSTED)) instanceof RoundStatus.Completed
    }

    def "FR19: a transform reply that is done states no answer, one that asks a decision states the decision request"() {
        when:
        def answer = dialect.answer(TRANSFORM, events(structured(reply)))

        then:
        answer.class == kind
        answer instanceof DialectAnswer.NoReply || (answer as DialectAnswer.Replied).document() == JSON.readTree(document)

        where:
        reply || kind | document
        '{"outcome":"done","question":"","options":[]}' || DialectAnswer.NoReply | null
        '{"outcome":"decision_request","question":"which db?","options":["pg","sqlite"]}' || DialectAnswer.Replied | '{"kind":"decision-request","question":"which db?","options":["pg","sqlite"]}'
        '{"outcome":"decision_request","question":"which db?","options":["pg"]}' || DialectAnswer.Replied | '{"kind":"decision-request","question":"which db?","options":["pg"]}'
    }

    // Scenario: executor/claude-dialect / A transform reply asks a decision through the schema
    def "FR19: a transform request hands the CLI the transform reply schema, and a reply asking a decision is the contract's decision request"() {
        when:
        def argv = dialect.render(TRANSFORM).argv()
        def answer = dialect.answer(TRANSFORM, events(structured(
                        '{"outcome":"decision_request","question":"which db?","options":["pg","sqlite"]}')))

        then: 'the argv ends with the transform reply schema'
        argv[-2] == '--json-schema'
        JSON.readTree(argv[-1]) == JSON.readTree('{"type":"object","additionalProperties":false,' +
                '"required":["outcome","question","options"],"properties":{' +
                '"outcome":{"type":"string","enum":["done","decision_request"]},' +
                '"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}}}}')

        and: 'the reply becomes the contract decision request with its question and options'
        (answer as DialectAnswer.Replied).document() == JSON.readTree(
                '{"kind":"decision-request","question":"which db?","options":["pg","sqlite"]}')
    }

    // Scenario: executor/claude-dialect / Every reply schema fits both schema families
    def "FR19: the #request.role() reply schema has an object root, closed objects requiring every property, and no root combinator"() {
        when:
        def argv = dialect.render(request).argv()
        def schema = JSON.readTree(argv[argv.indexOf('--json-schema') + 1])

        then:
        schema.type.asText() == 'object'
        !['anyOf', 'oneOf', 'allOf'].any { schema.has(it) }
        objects(schema).every { object ->
            object.get('additionalProperties')?.isBoolean() &&
            !object.get('additionalProperties').booleanValue() &&
            (object.get('required')*.asText() as Set) == (object.get('properties').fieldNames().toList() as Set)
        }
        !objects(schema).isEmpty()

        where:
        request << [TRANSFORM, VERDICT, DECISION]
    }

    def "FR19: the dialect declares the schema-enforced form for every role it describes, its instruction naming no answer file"() {
        expect:
        dialect.describe().roles().every { role ->
            def form = dialect.answerForm(role)
            form instanceof AnswerForm.SchemaEnforced &&
                    !form.instruction().contains('GNOMISH_ANSWER_FILE') &&
                    !form.instruction().contains('.gnomish-task') &&
                    form.instruction().contains('decision_request')
        }
    }

    /** Every object schema in {@code node}, itself included: any node whose type is {@code object}. */
    private static List objects(def node) {
        List found = []
        if (node.isObject()) {
            if (node.path('type').asText() == 'object') {
                found << node
            }
            node.elements().each { found.addAll(objects(it)) }
        } else if (node.isArray()) {
            node.elements().each { found.addAll(objects(it)) }
        }
        found
    }

    def "FR3: a round with no result event, or a role the dialect declares no schema for, states no answer"() {
        expect:
        dialect.answer(request, events(line)) instanceof DialectAnswer.NoReply

        where:
        request | line
        VERDICT | '{"type":"system","subtype":"init","session_id":"s","model":"m"}'
        EFFECT | structured('{"passed":true,"findings":[]}')
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
    static final ExecutorRequest EFFECT = new ExecutorRequest(Role.EFFECT, new Workspace('.'),
    new Policy.WorkspaceWrite(), RoleInputs.transform('ship'), ExecutorContractJson.settingsTree([:]), Bounds.none(), [], null)

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
