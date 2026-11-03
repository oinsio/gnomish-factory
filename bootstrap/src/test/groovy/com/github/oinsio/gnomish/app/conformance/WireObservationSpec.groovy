package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.app.describe.ProgramChannel
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.WireDocument
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.DenialRead
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR24 of define-executor-contract (design D19): the kit's {@link WireObservation} over an
 * environment double that records what it was asked and serves files the spec wrote. Documents are
 * noted from the command's variables, judged on the bytes the delegate read, and handed back
 * unchanged; a probing observation adds one field to a {@code start} or {@code poll} request and
 * changes nothing else; every other call passes through.
 */
class WireObservationSpec extends Specification {

    private final ExecHandle handle = Stub(ExecHandle)
    private final TaskExecutionEnvironment delegate = Mock(TaskExecutionEnvironment)

    private static final String REQUEST = ExecutorContractJson.writer().writeValueAsString(
    CaseJudgement.request(Role.TRANSFORM, new Workspace('/w/transform'), ExecutorContractJson.settingsTree([:])))

    private static ExecCommand operation(String word, String stdin = null) {
        new ExecCommand(['/opt/candidate', word], [
            (ProgramChannel.RESULT_FILE_VARIABLE): '/s/result.json',
            (ProgramChannel.ANSWER_FILE_VARIABLE): '/s/answer.json'], stdin, StderrPolicy.MERGED)
    }

    private static Optional<byte[]> bytes(String text) {
        Optional.of(text.getBytes(StandardCharsets.UTF_8))
    }

    // Scenario: executor/conformance-kit / Describe outside the schema fails the describe case
    def "FR24: a describe answer is judged against the describe schema on the bytes read, which pass through unchanged"() {
        given:
        def observation = new WireObservation(delegate, false)
        byte[] written = StandIn.data('executor-describe#break-describe').getBytes(StandardCharsets.UTF_8)
        delegate.readFile('/s/result.json', 65536) >> Optional.of(written)

        when:
        def started = observation.exec(operation(ProgramChannel.DESCRIBE))
        def read = observation.readFile('/s/result.json', 65536)

        then:
        1 * delegate.exec(_) >> handle
        started.is(handle)
        read.get() == written
        observation.breaches()*.document() == [WireDocument.DESCRIBE]
        observation.breaches().first().violation().path() == 'enforcementLevel'
        observation.breaches().first().detail().contains('the describe document breaks its schema at enforcementLevel')
        observation.describeDocument().path('enforcementLevel').asText() == 'SANDBOXED'
    }

    // Scenario: executor/conformance-kit / Observation leaves the factory's reads unchanged
    def "FR24: a start's request, result and answer are judged; valid documents leave no breach and the command goes out as it came"() {
        given:
        def observation = new WireObservation(delegate, false)
        def command = operation(ProgramChannel.START, REQUEST)
        delegate.readFile('/s/result.json', 10) >> bytes(StandIn.data('executor-result#transform'))
        delegate.readFile('/s/answer.json', 10) >> bytes('{"kind":"select","chosen":"a"}')

        when:
        def started = observation.exec(command)
        observation.readFile('/s/result.json', 10)
        observation.readFile('/s/answer.json', 10)

        then:
        1 * delegate.exec(command) >> handle
        started.is(handle)
        observation.breaches() == []
        observation.describeDocument() == null
    }

    // FR24: each document is judged once; a malformed result and answer are both kept, a later read of the same path is not judged again
    def "FR24: breaches of a result and an answer are kept in read order, a path is judged once, an absent file not at all"() {
        given:
        def observation = new WireObservation(delegate, false)
        delegate.readFile('/s/result.json', 10) >> bytes('{"status":"done"')
        delegate.readFile('/s/answer.json', 10) >> bytes(StandIn.data('executor-result#malformed-answer'))
        delegate.readFile('/s/absent.json', 10) >> Optional.empty()

        when:
        observation.exec(operation(ProgramChannel.POLL))
        observation.readFile('/s/result.json', 10)
        observation.readFile('/s/result.json', 10)
        observation.readFile('/s/answer.json', 10)
        observation.readFile('/s/absent.json', 10)

        then:
        observation.breaches()*.document().unique() == [
            WireDocument.RESULT,
            WireDocument.ANSWER
        ]
        observation.breaches().first().violation().keyword() == 'json'
    }

    // Scenario: executor/conformance-kit / Unknown request field reaches the candidate
    // Scenario: executor/wire-contract / Older executor survives a new optional field
    def "FR24: a probing observation adds the probe field once to a #word request and changes nothing else"() {
        given:
        def observation = new WireObservation(delegate, true)
        ExecCommand sent = null

        when:
        observation.exec(operation(word, REQUEST))

        then:
        1 * delegate.exec(_) >> { ExecCommand c -> sent = c; handle }
        def tree = ExecutorContractJson.reader().readTree(sent.stdin())
        tree.path(WireObservation.PROBE_FIELD).isTextual()
        tree.remove(WireObservation.PROBE_FIELD) != null
        tree == ExecutorContractJson.reader().readTree(REQUEST)
        sent.command() == ['/opt/candidate', word]
        sent.env() == operation(word).env()
        observation.breaches() == []

        where:
        word << [
            ProgramChannel.START,
            ProgramChannel.POLL
        ]
    }

    // FR24: a factory request outside its schema is a breach too, judged before the probe is added
    def "FR24: a rendered request the request schema refuses is kept as a request breach"() {
        given:
        def observation = new WireObservation(delegate, true)

        when:
        observation.exec(operation(ProgramChannel.START, '{"role":"transform"}'))

        then:
        1 * delegate.exec(_) >> handle
        observation.breaches()*.document().unique() == [WireDocument.REQUEST]
    }
}
