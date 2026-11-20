package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.stateAt
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED

import com.github.oinsio.gnomish.adapter.check.LoopbackTlsFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The pollable check of the pending-poll kill windows on the real medium (design D21 of
 * migrate-verify-to-executor-contract, task 4.3): an {@code http} built-in judging a gate on an
 * in-JVM WireMock server over loopback TLS (the egress guard admits {@code https} only), reached under the operator's {@code loopback} in-process scope. The gate
 * answers {@code RUNNING} ({@code pending-when}) or {@code OK} ({@code pass-when}) in the order a
 * row scripts, so a {@code start} that answers {@code pending} lands a pending commit of its own —
 * the medium's record that a check started. A {@code start} and a {@code poll} of this built-in
 * send the same request (it is stateless), so the request count says how often the gate was asked
 * and the pending commits say whether any ask was a {@code start}.
 *
 * <p>The dying instance's writes are the production ones: the round's snapshot ({@code
 * HostVerificationKill.killAfterSnapshot}), then its persistence's pending write over the run's
 * cell, pushed best-effort, as {@code PendingPoll} hands it the last persisted state (D21 item 12).
 */
final class PendingVerificationKill {

    static final String GATE = '/gate'

    /** The virtual clock the take routes run on starts at the epoch: an entry's deadline counts from it. */
    static final Instant SINCE = Instant.EPOCH

    private PendingVerificationKill() {}

    /**
     * The {@code http} check of {@code gate}'s endpoint {@code path}: pending while RUNNING, passing on
     * OK, polled every {@code interval} — a spec on real time (the Docker layer) asks for a short one.
     */
    static VerifyCheck.Executor gate(WireMockServer gate, String id = 'gate', String path = GATE,
            Duration interval = Duration.ofSeconds(30)) {
        VerifyChecks.external(id, 'http', [
            url: "https://127.0.0.1:${gate.httpsPort()}${path}".toString(),
            'pass-when': ['json-path': 'status', equals: 'OK'],
            'pending-when': ['json-path': 'status', equals: 'RUNNING'],
        ], interval, Duration.ofMinutes(10), VerifyCheck.TimeoutClass.QUALITY, [])
    }

    /** A started gate on loopback TLS, its certificate trusted by the JVM until {@link LoopbackTlsFixture#restore}. */
    static WireMockServer tlsGate(Path directory) {
        def keystore = LoopbackTlsFixture.keystore(Files.createDirectories(directory))
        LoopbackTlsFixture.install(keystore)
        def server = new WireMockServer(WireMockConfiguration.options().dynamicPort().dynamicHttpsPort()
                .keystorePath(keystore.toString()).keystoreType('PKCS12')
                .keystorePassword(LoopbackTlsFixture.PASSWORD).keyManagerPassword(LoopbackTlsFixture.PASSWORD))
        server.start()
        server
    }

    /** One automatic stage per name, each verified by {@code checks}. */
    static PipelineDefinition gated(List<VerifyCheck> checks, String... names) {
        new PipelineDefinition('1', new AutonomyLimits(1), names.collect { String name ->
            new StageDefinition(name, 'purpose', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
            'instructions.md', checks, new AutonomyLimits(1), AdvancementMode.AUTO)
        })
    }

    /** The gate at {@code path} answers {@code statuses} in order, the last one from then on. */
    static void script(WireMockServer gate, List<String> statuses, String path = GATE) {
        statuses.eachWithIndex { String status, int i ->
            def stub = get(urlEqualTo(path)).inScenario(path).whenScenarioStateIs(i == 0 ? STARTED : "asked-${i}")
            if (i + 1 <statuses.size()) {
                stub = stub.willSetStateTo("asked-${i + 1}")
            }
            gate.stubFor(stub.willReturn(aResponse().withStatus(200).withHeader('Content-Type', 'application/json')
                    .withBody("{\"status\":\"${status}\"}")))
        }
    }

    /**
     * The dying instance's pending write of {@code entries} — {@code [check, key]} of the round's
     * checks, each under the round's attempt and token, or {@code [check, key, token]} for an entry
     * another round left — over the state its snapshot registered, byte for byte (D21 item 12). The
     * subject names the last entry's round, so a stale entry is never the last.
     *
     * @return the entries the pending commit carries
     */
    static List<PendingPollRecord> landEntries(TakeKillPointWorld world, CurrentRound cell, int round,
            List<List<Object>> entries) {
        def records = records(world, cell, round, entries)
        def registered = stateAt(world.worktree, cell.closed().registered())
        world.ownGit.store().attemptPersistence(world.worktree, world.taskId, cell)
                .persistPending(world.taskId, registered.withPendingPolls(records))
        records
    }

    /**
     * The entries {@code [check, key]} or {@code [check, key, token]} of the world's checks, as a
     * pending write of round {@code round} of {@code cell} records them — on either medium.
     */
    static List<PendingPollRecord> records(TakeKillPointWorld world, CurrentRound cell, int round,
            List<List<Object>> entries) {
        entries.collect { List<Object> entry ->
            def check = entry[0] as VerifyCheck
            new PendingPollRecord(CheckRef.of(world.definition.stages().first().verify().indexOf(check), check), round,
                    entry.size() > 2 ? entry[2] as RoundToken : cell.opened(), new PendingKey(entry[1] as String), SINCE)
        }
    }

    /** Runs {@code write} while {@code origin} cannot be reached, so its best-effort push fails (W1′). */
    static void withOriginGone(Path origin, Closure write) {
        Path away = origin.resolveSibling('origin.away')
        Files.move(origin, away)
        try {
            write.call()
        } finally {
            Files.move(away, origin)
        }
    }

    /** The commit {@code ref} names in {@code repo}, or {@code null} when it names none. */
    static String commitOf(Path repo, String ref) {
        def rev = new GitProcessRunner().run(repo, 'rev-parse', '--verify', '--quiet', ref)
        rev.exitCode() == 0 ? rev.stdout().forParsing().trim() : null
    }

    /** The newest commit on the task branch whose subject starts with {@code prefix}. */
    static String newestWith(Path repo, String taskId, String prefix) {
        def log = new GitProcessRunner().run(repo, 'log', '--format=%H %s', "gnomish/${taskId}".toString())
        assert log.exitCode() == 0
        log.stdout().forParsing().readLines().find {
            it.substring(41).startsWith(prefix)
        }?.substring(0, 40)
    }

    /** The subjects of {@code repo}'s commits above {@code below} on the task branch, oldest first. */
    static List<String> subjectsAbove(Path repo, String taskId, String below) {
        def log = new GitProcessRunner().run(repo, 'log', '--reverse', '--format=%s', "${below}..gnomish/${taskId}".toString())
        assert log.exitCode() == 0
        log.stdout().forParsing().readLines()
    }
}
