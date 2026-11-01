package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.DecisionRequestRead
import com.github.oinsio.gnomish.executorcontract.ResultFileReader
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, NFR-S2 of define-executor-contract: the host decision file is read through the executor
 * contract's result reader, by the same {@code DecisionRequestRead} the box and the snapshot
 * recovery use. What the host medium gained by the move: the one-MiB cap (the read had none),
 * the truncation marker, a symbolic link at the path refused rather than followed — and it keeps
 * the tolerant fallback it had.
 */
class DecisionFileTransportResultReadSpec extends Specification {

    @TempDir
    Path workspaceRoot

    // Scenario: executor/result-channel / Garbage decision file keeps its fallback
    def "FR6: garbage in the host decision file becomes the question verbatim, read through the shared reader"() {
        given:
        def handle = new DecisionFileTransport().open()
        Files.writeString(handle.decisionFilePath(), 'no json here, only words')

        when:
        def logs = LogCaptureSupport.attach(DecisionRequestRead)
        def request = handle.readAndClose()
        logs.detach()

        then:
        request.get().question().forLog() == 'no json here, only words'
        request.get().question().provenance() == Provenance.AGENT
        request.get().options().isEmpty()
    }

    // FR6 of define-executor-contract: the host read had no cap before it moved onto the owner.
    // Scenario: executor/agent-executor / Decision file read is capped
    def "FR6: a host decision file over the cap is read up to the cap, marked, and still asks"() {
        given:
        def cap = ResultFileReader.DECISION_FILE_CAP_BYTES
        def handle = new DecisionFileTransport().open()
        def tempDir = handle.decisionFilePath().parent
        Files.writeString(handle.decisionFilePath(), '{"question": "' + ('q' * (2 * cap)) + '"}')

        when:
        def logs = LogCaptureSupport.attach(DecisionRequestRead)
        def request = handle.readAndClose()
        logs.detach()

        then: 'the cut text is the question, ending with the reader\'s truncation marker'
        def marker = new ResultFileReader(cap, { String text ->
            UntrustedText.agent(text)
        }).truncationMarker()
        request.get().question().length() == cap + marker.length()
        request.get().options().isEmpty()

        and: 'the cleanup ran as for any read'
        !Files.exists(tempDir)
    }

    // FR6, NFR-S2 of define-executor-contract: a link planted at the path is refused, not followed.
    def "FR6: a symbolic link at the decision path is refused, and discard still cleans the round up"() {
        given: 'a link at the decision path pointing at a file outside the round directory'
        def outside = Files.writeString(workspaceRoot.resolve('secret.txt'), '{"question": "leaked?"}')
        def handle = new DecisionFileTransport().open()
        def tempDir = handle.decisionFilePath().parent
        Files.createSymbolicLink(handle.decisionFilePath(), outside)

        when:
        handle.readAndClose()

        then:
        thrown(UncheckedIOException)

        when: 'the caller takes the infrastructure-failure path'
        handle.discard()

        then:
        !Files.exists(tempDir)
        Files.readString(outside) == '{"question": "leaked?"}'
    }
}
