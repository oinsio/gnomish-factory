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
 * FR24 of define-executor-contract (design D19): what the kit's {@link WireObservation} leaves alone
 * — a command that is no program operation, a start that carries no request, and every other call
 * of the port — reaches the environment it decorates as it came.
 */
class WireObservationPassThroughSpec extends Specification {

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

    // FR24: a command with no result variable — the working-copy check's git status — and a describe pass through untouched
    def "FR24: #shape passes through as it came and notes nothing"() {
        given:
        def observation = new WireObservation(delegate, true)

        when:
        observation.exec(command)
        observation.readFile('/s/result.json', 10)

        then:
        1 * delegate.exec(command) >> handle
        1 * delegate.readFile('/s/result.json', 10) >> bytes('not json')
        observation.breaches() == []

        where:
        shape | command
        'a command without the result variable' | ExecCommand.of([
            'git',
            'status',
            '--porcelain=v2'
        ])
    }

    // FR24: a start that carries no request goes out as it came, and its result is still judged
    def "FR24: a start without a request passes through unchanged, its result still noted"() {
        given:
        def observation = new WireObservation(delegate, true)
        def command = operation(ProgramChannel.START, null)

        when:
        def started = observation.exec(command)
        observation.readFile('/s/result.json', 10)

        then:
        1 * delegate.exec(command) >> handle
        1 * delegate.readFile('/s/result.json', 10) >> bytes('not json')
        started.is(handle)
        observation.breaches()*.document() == [WireDocument.RESULT]
    }

    // FR24: every other call of the port reaches the delegate unchanged
    def "FR24: the rest of the port is the delegate's"() {
        given:
        def observation = new WireObservation(delegate, false)
        def passport = CapabilityPassport.hostNoIsolation()
        def restoration = new DenialRestoration(Optional.empty(), [] as Set)
        delegate.scratchRoot() >> '/scratch'
        delegate.passport() >> passport
        delegate.readDenials() >> DenialRead.none()
        delegate.denialCursor() >> Optional.of(new com.github.oinsio.gnomish.sandbox.DenialCursor('guard-1', '42'))

        when:
        observation.materialize('conformance', 'abc')
        observation.putFile('/s/x', [1] as byte[])
        observation.harvest()
        observation.probeGuard()
        observation.restoreDenials(restoration)
        observation.dispose()
        def answers = [
            observation.scratchRoot(),
            observation.passport(),
            observation.readDenials(),
            observation.denialCursor()
        ]

        then:
        1 * delegate.materialize('conformance', 'abc')
        1 * delegate.putFile('/s/x', [1] as byte[])
        1 * delegate.harvest()
        1 * delegate.probeGuard()
        1 * delegate.restoreDenials(restoration)
        1 * delegate.dispose()
        answers == [
            '/scratch',
            passport,
            DenialRead.none(),
            Optional.of(new com.github.oinsio.gnomish.sandbox.DenialCursor('guard-1', '42'))
        ]
    }
}
