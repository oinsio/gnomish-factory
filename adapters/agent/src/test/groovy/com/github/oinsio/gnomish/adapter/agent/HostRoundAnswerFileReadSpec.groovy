package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.DecisionRequestRead
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.ResultFileReader
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9 of extract-agent-round-runner (design D3); FR6, NFR-S2 of define-executor-contract: a host
 * round's answer file is read through the one tolerant {@link DecisionRequestRead} over the
 * executor contract's result reader, as the branch medium's is — the tolerant fallback, the cap
 * and its truncation marker, a symbolic link at the path refused rather than followed.
 */
class HostRoundAnswerFileReadSpec extends Specification {

    @TempDir
    Path workspaceDir

    @TempDir
    Path outsideDir

    HostRoundEnvironmentSource source = new HostRoundEnvironmentSource(new VirtualClock(), TestChildEnvironment.hostAllowlist())

    // Scenario: executor/result-channel / Garbage decision file keeps its fallback
    def "FR9: garbage at the answer file becomes the question verbatim, read through the shared reader"() {
        given:
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))
        Files.writeString(round.answerFilePath(), 'no json here, only words')
        def logs = LogCaptureSupport.attach(DecisionRequestRead)

        when:
        round.closeRound(RoundClose.VERIFIABLE)

        then:
        def request = round.readDecision().get()
        request.question().forLog() == 'no json here, only words'
        request.question().provenance() == Provenance.AGENT
        request.options().isEmpty()
        logs.list.any {
            it.formattedMessage.startsWith(OperatorEvent.DECISION_FILE_NOT_JSON.head())
        }

        cleanup:
        logs.detach()
    }

    // FR9: the host medium takes the shared reader's cap and truncation marker, and still asks
    def "FR9: an answer file over the cap is read up to the cap, marked, and still asks"() {
        given:
        def cap = ResultFileReader.DECISION_FILE_CAP_BYTES
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))
        Files.writeString(round.answerFilePath(), '{"question": "' + ('q' * (2 * cap)) + '"}')
        def reads = LogCaptureSupport.attach(DecisionRequestRead)
        def channel = LogCaptureSupport.attach(HostTaskExecutionEnvironment)

        when:
        round.closeRound(RoundClose.VERIFIABLE)

        then: 'the cut text is the question, ending with the reader\'s truncation marker'
        def marker = new ResultFileReader(cap, { String text ->
            UntrustedText.agent(text)
        }).truncationMarker()
        def request = round.readDecision().get()
        request.question().length() == cap + marker.length()
        request.options().isEmpty()
        channel.list.any {
            it.formattedMessage.startsWith(OperatorEvent.HOST_CHANNEL_FILE_TRUNCATED.head())
        }

        cleanup:
        reads.detach()
        channel.detach()
    }

    // FR9, NFR-S2 of define-executor-contract: a link planted at the path is refused, not followed
    def "FR9: a symbolic link at the answer file is refused, and the discard still removes the round"() {
        given: 'a link at the answer file pointing at a file outside the round\'s roots'
        def outside = Files.writeString(outsideDir.resolve('secret.txt'), '{"question": "leaked?"}')
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))
        def scratch = Path.of(round.environment().scratchRoot())
        Files.createSymbolicLink(round.answerFilePath(), outside)

        when:
        round.closeRound(RoundClose.VERIFIABLE)

        then:
        thrown(RuntimeException)

        when: 'the caller takes the infrastructure-failure path'
        round.discard()

        then:
        !Files.exists(scratch)
        Files.readString(outside) == '{"question": "leaked?"}'
    }
}
