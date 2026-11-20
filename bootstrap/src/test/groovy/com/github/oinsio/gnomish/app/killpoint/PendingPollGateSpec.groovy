package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef
import static com.github.oinsio.gnomish.app.HostVerificationKill.stateAt
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.check.LoopbackTlsFixture
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.tomakehurst.wiremock.WireMockServer
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import javax.net.ssl.SSLContext
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The gate every pending-poll kill window of migrate-verify-to-executor-contract is judged by
 * (design D21, D22; tasks 4.3 and 4.3b), whatever the medium: the {@code http} gate on loopback TLS
 * ({@link PendingVerificationKill}), its request count, the register the clone holds at each request
 * the gate sees, the dying instance's own ask of the gate, and the readings of a round's close. The
 * medium's world, its pickup and its second recovery pass are the subclass's.
 */
abstract class PendingPollGateSpec extends Specification implements TakeKillPointWorlds {

    static final String TASK = TAKE_TASK_ID

    @TempDir
    Path tempDir

    WireMockServer server

    SSLContext previous = SSLContext.getDefault()

    /** The clone whose register {@link #registersAsked} reads at each request; set by a row that records it. */
    Path observed

    /** The register {@link #observed} named when the gate saw each request, in order. */
    List<String> registersAsked = []

    def setup() {
        server = tlsGate(tempDir.resolve('tls'))
        server.addMockServiceRequestListener { request, response ->
            if (observed != null) {
                registersAsked << commitOf(observed, registerRef(TASK))
            }
        }
    }

    def cleanup() {
        server.stop()
        LoopbackTlsFixture.restore(previous)
    }

    /** The requests the gate at {@code path} has seen. */
    int asked(String path = GATE) {
        server.countRequestsMatching(getRequestedFor(urlEqualTo(path)).build()).count
    }

    /** The dying instance's own ask of the gate before the kill: a start, or a poll, whose answer it never recorded. */
    void dyingAsk(String path = GATE) {
        def request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:${server.httpsPort()}${path}")).GET().build()
        assert HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    }

    /** The state at {@code commit} in {@code repo}. */
    static TaskState at(Path repo, String commit) {
        stateAt(repo, commit)
    }

    /** The round's closing commit above {@code below} passed round zero, burned nothing and dropped every entry. */
    static boolean closedPassing(Path repo, String close) {
        def state = stateAt(repo, close)
        state.attempts()*.result() == [AttemptRecord.Result.PASSED] && state.attempts()*.round() == [0] &&
        state.attemptsUsed() == 0 && state.pendingPolls().isEmpty()
    }
}
