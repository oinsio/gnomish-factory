package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef
import static com.github.oinsio.gnomish.app.HostVerificationKill.stateAt
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.EgressProperties
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.LoopbackTlsFixture
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.tomakehurst.wiremock.WireMockServer
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.SSLContext
/**
 * The world of the container pending-poll windows picked up on another clone (task 4.3b of
 * migrate-verify-to-executor-contract; design D21, D22; NFR-R1, NFR-R4): a production container
 * {@code run} — the fake agent in a real box, the {@code http} gate of {@code PendingVerificationKill}
 * judged in process under the operator's {@code loopback} scope — dies at a kill window of its
 * verification ({@link PendingKills}), and {@code run --resume} over a fresh clone of the Gitea origin
 * picks it up. Another clone is another instance's host: the dying instance's box is not there, so
 * its Docker objects are removed before the pickup. Every row ends with a second recovery pass that
 * changes nothing. The concrete spec carries the Docker gate and the timeout.
 */
abstract class PendingPollOtherCloneE2ESpecBase extends ContainerParkSpecBase {

    WireMockServer server

    SSLContext previous = SSLContext.getDefault()

    def setup() {
        server = tlsGate(tempDir.resolve('tls'))
    }

    def cleanup() {
        server.stop()
        LoopbackTlsFixture.restore(previous)
    }

    /** The fake agent's binary, and the operator's in-process scope the gate is judged under (D16). */
    @Override
    FactoryProperties factoryProperties() {
        new FactoryProperties('test-instance', FakeAgentSandboxImage.BINARY, null, null, null, null, null, null,
                new EgressProperties([], ['loopback']))
    }

    /** A fresh clone of the real remote on another instance's host: the dying host's Docker objects are not there. */
    Path otherClone() {
        ContainerE2eDocker.removeTaskObjects(taskId)
        def parent = Files.createDirectories(tempDir.resolve('b'))
        seedClone(parent, originUrl, parent.resolve('container-project'))
    }

    /** The commit right above {@code below} on the task branch of {@code repo}. */
    String closeAboveIn(Path repo, String below) {
        gitOutput(repo, 'rev-list', '--reverse', "${below}..${branch()}").readLines().first()
    }

    /** What origin names {@code ref}, read without a fetch. */
    String remote(String ref) {
        def line = gitOutput(cloneDir, 'ls-remote', 'origin', ref)
        line.isBlank() ? null : line.split(/\s+/)[0]
    }

    int asked() {
        server.countRequestsMatching(getRequestedFor(urlEqualTo(GATE)).build()).count
    }

    /** The round's close passed round zero, burned nothing and dropped every entry. */
    static boolean closedPassing(Path repo, String close) {
        def state = stateAt(repo, close)
        state.attempts()*.result() == [AttemptRecord.Result.PASSED] && state.attempts()*.round() == [0] &&
        state.attemptsUsed() == 0 && state.pendingPolls().isEmpty()
    }

    /** The second recovery pass: a further resume on the same clone changes neither replica nor asks the gate. */
    boolean secondPassIsNoOp(pipeline, Path other) {
        def footprint = {
            ->
            [
                tip: commitOf(other, "refs/heads/${branch()}"),
                originTip: remote("refs/heads/${branch()}"),
                register: remote(registerRef(taskId)),
                asked: server.allServeEvents.size(),
            ]
        }
        def before = footprint()
        resume(pipeline, other)
        footprint() == before
    }
}
