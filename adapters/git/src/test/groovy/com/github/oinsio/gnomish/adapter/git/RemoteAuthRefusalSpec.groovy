package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir

/**
 * An origin that answers and refuses the factory's credentials: the case
 * {@code docs/adr/0005-dependency-outage-accounting.md} classifies as the daemon's, not the task's.
 *
 * <p>A revoked, expired or mis-scoped daemon credential fails the refs read itself, before any ref
 * is confirmed — so the base refresh, the default-branch discovery and the outage gate's own probe
 * all fail together, and no task caused or can fix it. Parking task after task for that would park
 * the whole backlog one report at a time, which is the ending the outage principle exists to
 * prevent. It therefore opens the remote outage gate and keeps it open until a human replaces the
 * credential.
 *
 * <p>The contrasting half — a remote that answered, confirmed the ref, and then refused the fetch
 * of that one ref — stays the task's class and is pinned by {@link BaseRefreshSpec}'s
 * {@code fetchAlwaysFails} features through {@link OriginProbe}. Nothing here parses git's words to
 * tell the two apart: the CLI exits 128 for both, and the probe's behavior is the whole mechanism.
 *
 * <p>A real HTTP server rather than a scripted git stand-in, because the claim under test is about
 * what a <em>remote</em> does: the request counter below is the evidence that origin genuinely
 * answered and refused, which no exit-code fake could establish.
 *
 * <p>FR5, FR9, FR14 of add-base-ref-resolution.
 */
class RemoteAuthRefusalSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    /** Every request origin actually served — the proof that the remote answered rather than died. */
    private final AtomicInteger requests = new AtomicInteger()

    private HttpServer origin

    def setup() {
        origin = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        origin.createContext('/', { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add('WWW-Authenticate', 'Basic realm="gnomish"')
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        } as HttpHandler)
        origin.start()
    }

    def cleanup() {
        origin?.stop(0)
    }

    def "FR9: a credential the remote refuses is the daemon's class, not the task's (#shape)"() {
        given: 'a clone whose origin answers every read with 401'
        def clone = cloneOfRefusingOrigin(dir, userInfo)

        when: 'the base is refreshed for a claim'
        def outcome = baseRefs().refresh(clone, 'develop')

        then: 'origin answered, and refused before any ref could be confirmed'
        requests.get() > 0
        outcome instanceof BaseRefreshOutcome.Unavailable

        and: 'so the claim is released to the daemon, never parked against the task'
        !(outcome instanceof BaseRefreshOutcome.Refused)

        where:
        shape | dir | userInfo
        'a rejected token' | 'rejected' | 'gnome:badtoken@'
        'no credentials at all' | 'absent' | ''
    }

    def "FR5: the default-branch discovery of a refusing origin takes the same class"() {
        given:
        def clone = cloneOfRefusingOrigin('discovery', 'gnome:badtoken@')

        when: 'startup asks origin which branch it calls its default'
        def discovered = baseRefs().discoverDefaultBranch(clone)

        then: 'the same condition, so the same class — no per-task report for a daemon credential'
        requests.get() > 0
        discovered instanceof DefaultBranchDiscovery.Unavailable
    }

    def "FR14: the outage gate's probe cannot pass while the credential is refused"() {
        given:
        def clone = cloneOfRefusingOrigin('probe', 'gnome:badtoken@')

        expect: 'the recovery signal stays negative: the gate stays open until a human replaces it'
        !baseRefs().probe(clone)
        requests.get() > 0
    }

    def "the bounded infrastructure retry is spent before the class is decided"() {
        given: 'the argv log that shows what the read COSTS, which its outcome cannot'
        Path log = tempDir.resolve('argv.log')
        def clone = cloneOfRefusingOrigin('budget', 'gnome:badtoken@')

        when:
        baseRefs(new GitProcessRunner(recordingGit(log).toString())).refresh(clone, 'develop')

        then: 'a refused read is unsettled in the retry sense, so the whole budget goes to it'
        recordedSubcommands(log).count('ls-remote') == GitInfrastructureRetry.DEFAULT_ATTEMPTS
    }

    /**
     * A repo whose {@code origin} is the refusing server, reached with {@code userInfo} (empty for
     * no credentials at all, which {@code GIT_TERMINAL_PROMPT=0} turns into an immediate refusal
     * rather than a wait).
     */
    private Path cloneOfRefusingOrigin(String name, String userInfo) {
        Path repo = initWorkingRepo(tempDir, name)
        commit(repo, 'a.txt', 'seed')
        // No credential helper: this origin must be refused, never satisfied out of a developer's
        // keychain, which macOS wires in globally.
        assert gitExitCode(repo, 'config', 'credential.helper', '') == 0
        addRemote(repo, 'origin', "http://${userInfo}127.0.0.1:${origin.address.port}/owner/repo.git")
        repo
    }

    /** The real port realization on virtual time, so the production budget elapses instantly. */
    private static GitBaseRefs baseRefs(GitProcessRunner runner = new GitProcessRunner()) {
        new GitBaseRefs(runner, VirtualTimeGitRetries.gitInfrastructure())
    }
}
