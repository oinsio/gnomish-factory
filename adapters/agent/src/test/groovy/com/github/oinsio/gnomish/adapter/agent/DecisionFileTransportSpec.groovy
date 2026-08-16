package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentInvocation
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, NFR-R3, NFR-S2, D1: {@link DecisionFileTransport} owns the per-round
 * decision-file temp-directory lifecycle — create outside the workspace,
 * expose the env-var fragment, read raw content after process exit, delete.
 * Tolerant parsing of the raw content (garbage / empty-file fallback text) is
 * task 6.3's job — these specs only prove presence/absence and cleanup.
 */
class DecisionFileTransportSpec extends Specification {

    @TempDir
    Path workspaceRoot

    def "the decision file path lives outside the workspace root"() {
        given:
        def transport = new DecisionFileTransport()

        when:
        def handle = transport.open()

        then: 'NFR-S2: the temp dir is not nested under the workspace'
        !handle.decisionFilePath().startsWith(workspaceRoot)

        cleanup:
        handle.readAndClose()
    }

    def "the env fragment carries GNOMISH_DECISION_FILE pointing at the decision file path"() {
        given:
        def transport = new DecisionFileTransport()

        when:
        def handle = transport.open()

        then:
        handle.envFragment() == ['GNOMISH_DECISION_FILE': handle.decisionFilePath().toString()]

        cleanup:
        handle.readAndClose()
    }

    def "readAndClose returns empty when the decision file was never written"() {
        given:
        def transport = new DecisionFileTransport()
        def handle = transport.open()

        when:
        def content = handle.readAndClose()

        then:
        content.isEmpty()
    }

    def "readAndClose returns the raw content when the decision file was written"() {
        given:
        def transport = new DecisionFileTransport()
        def handle = transport.open()
        Files.writeString(handle.decisionFilePath(), '{"question": "Refactor or patch?", "options": ["refactor", "patch"]}')

        when:
        def content = handle.readAndClose()

        then:
        content.isPresent()
        content.get().contains('Refactor or patch?')
    }

    def "readAndClose deletes the temp directory, present or absent"() {
        given:
        def transport = new DecisionFileTransport()
        def handle = transport.open()
        def tempDir = handle.decisionFilePath().parent
        Files.writeString(handle.decisionFilePath(), 'garbage')

        when:
        handle.readAndClose()

        then: 'NFR-R3: the round directory no longer exists once the lifecycle completes'
        !Files.exists(tempDir)
    }

    def "two consecutive rounds get distinct decision-file paths and the prior directory is gone"() {
        given:
        def transport = new DecisionFileTransport()

        when:
        def firstHandle = transport.open()
        def firstDir = firstHandle.decisionFilePath().parent
        firstHandle.readAndClose()

        def secondHandle = transport.open()

        then: 'NFR-R3: no stale file/directory across rounds'
        secondHandle.decisionFilePath() != firstHandle.decisionFilePath()
        !Files.exists(firstDir)

        cleanup:
        secondHandle.readAndClose()
    }

    def "a transport rooted at a given parent creates its round directory under that parent"() {
        given: 'the package-private testing seam roots rounds at an explicit directory'
        def transport = new DecisionFileTransport(workspaceRoot)

        when:
        def handle = transport.open()

        then: 'the fresh round directory is created directly under the supplied parent'
        handle.decisionFilePath().parent.parent == workspaceRoot

        cleanup:
        handle.discard()
    }

    def "discard deletes the round's temp directory without reading the file first"() {
        given:
        def transport = new DecisionFileTransport()
        def handle = transport.open()
        def tempDir = handle.decisionFilePath().parent
        Files.writeString(handle.decisionFilePath(), 'unread garbage')

        when: 'the infrastructure-failure cleanup path runs instead of readAndClose'
        handle.discard()

        then: 'NFR-R3, D1: the round directory is gone even though it was never read'
        !Files.exists(tempDir)
    }

    def "discard is safe after readAndClose and on repeated calls"() {
        given:
        def transport = new DecisionFileTransport()
        def handle = transport.open()

        when:
        handle.readAndClose()
        handle.discard()
        handle.discard()

        then:
        noExceptionThrown()
    }

    def "readAndClose is idempotent-safe to call once and leaves no directory behind on repeated calls"() {
        given:
        def transport = new DecisionFileTransport()
        def handle = transport.open()

        when:
        handle.readAndClose()
        handle.readAndClose()

        then:
        noExceptionThrown()
    }

    def "end to end: the fake agent binary writes to the transport-provided path via the env fragment"() {
        given:
        def transport = new DecisionFileTransport()
        def handle = transport.open()
        def invocation = new FakeAgentInvocation(
                scenario: 'decision-needed',
                workingDirectory: workspaceRoot,
                decisionFilePath: handle.decisionFilePath())

        when:
        def result = invocation.run()
        def content = handle.readAndClose()

        then:
        result.exitCode() == 0
        content.isPresent()
        content.get().contains('"question": "Refactor or patch?"')
    }
}
