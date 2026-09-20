package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.github.oinsio.gnomish.app.port.git.OriginContact
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * FR14, D9 of add-base-ref-resolution: the serve slot's base reads report to the remote outage
 * gate at the instant they return — an outage opens it, a refresh confirms recovery, a refusal
 * says nothing — and every outcome is forwarded to the caller unchanged. The gate here is real
 * (on virtual time), so what is asserted is the gate's observable state, not "method calls
 * method".
 */
class RemoteOutageSignalingBaseRefGitSpec extends Specification {

    private static final Path CLONE = Path.of('.')
    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final Random NO_JITTER = new Random() {
        double nextDouble() {
            0.0
        }
    }

    private VirtualClock clock = new VirtualClock()
    private RemoteOutageGate gate = new RemoteOutageGate(
    BaseRefGit.UNWIRED, CLONE, clock, new Random(0), IDLE, Duration.ofMinutes(10))

    private BaseRefGit signaling(BaseRefGit delegate) {
        new RemoteOutageSignalingBaseRefGit(delegate, gate)
    }

    // FR14: an outage met by the fresh-claim refresh opens the gate right there, with the
    //     outcome still handed back so the claim releases as before.
    def "an unavailable refresh opens the gate and is forwarded"() {
        given:
        def outage = new BaseRefreshOutcome.Unavailable(UntrustedText.subprocess('connection refused'))
        def git = signaling([refresh: { Path d, String r ->
                outage
            }] as BaseRefGit)

        when:
        def outcome = git.refresh(CLONE, 'main')

        then:
        outcome.is(outage)
        gate.isOpen()
        gate.health().lastError() == 'connection refused'
    }

    // FR2, FR3 of signal-outage-gate-on-origin-contact: a successful refresh is the "successful
    //     base refresh" signal ONLY when it reached origin, stamped at the read's own instant —
    //     the fact's time, not the slot's end. A base the clone already held answers with no round
    //     trip and says nothing about the remote, so it neither stamps the contact time nor spends
    //     the pending interval reset; either way the outcome is forwarded unchanged.
    def "a successful refresh confirms the refresh only when it contacted origin, and is forwarded"() {
        given:
        def refreshed = new BaseRefreshOutcome.Refreshed('main', 'abc', BaseRefKind.BRANCH, contact)
        def git = signaling([refresh: { Path d, String r ->
                refreshed
            }] as BaseRefGit)
        clock.advance(Duration.ofMinutes(5))

        when:
        def outcome = git.refresh(CLONE, 'main')

        then:
        outcome.is(refreshed)
        !gate.isOpen()
        gate.health().lastSuccessAt() == (confirms ? clock.now() : null)

        where:
        contact | confirms
        OriginContact.CONTACTED | true
        OriginContact.CLONE_ONLY | false
    }

    // FR14: a refusal means origin answered but no base was refreshed — neither signal fires.
    def "a refused refresh signals nothing and is forwarded"() {
        given:
        def refused = new BaseRefreshOutcome.Refused(UntrustedText.subprocess('no such ref'))
        def git = signaling([refresh: { Path d, String r ->
                refused
            }] as BaseRefGit)

        when:
        def outcome = git.refresh(CLONE, 'main')

        then:
        outcome.is(refused)
        !gate.isOpen()
        gate.health().lastSuccessAt() == null
    }

    // FR14, D13 of add-base-ref-resolution; FR2, FR3 of signal-outage-gate-on-origin-contact: the
    //     resume rebind is the other claim-time base read — same three arms, and its success arm
    //     draws the same origin-contact distinction as the refresh above.
    def "a resume rebind signals the gate by its outcome arm"() {
        given:
        def git = signaling([resolveForResume: { Path d, String r, BaseRefKind k ->
                outcome
            }] as BaseRefGit)

        when:
        def forwarded = git.resolveForResume(CLONE, 'main', BaseRefKind.BRANCH)

        then:
        forwarded.is(outcome)
        gate.isOpen() == opens
        (gate.health().lastSuccessAt() != null) == confirms

        where:
        outcome | opens | confirms
        new ResumeBaseOutcome.Unavailable(UntrustedText.subprocess('timed out')) | true | false
        new ResumeBaseOutcome.Bound('main', 'abc', OriginContact.CONTACTED) | false | true
        new ResumeBaseOutcome.Bound('main', 'abc', OriginContact.CLONE_ONLY) | false | false
        new ResumeBaseOutcome.Refused(UntrustedText.subprocess('gone')) | false | false
    }

    // UX1, FR2, FR3, NFR-O1 of signal-outage-gate-on-origin-contact — the operator-facing promise,
    //     carried entirely by the existing remote health: no new log line, operator-event code,
    //     ledger line or snapshot field, only an accurate lastSuccessAt (NFR-O1). Driven
    //     through a real gate on virtual time: after an outage closes, a base served by the clone
    //     alone must NOT spend the pending interval reset, so the next flap meets the grown pause
    //     FR14 of add-base-ref-resolution promised rather than the idle floor. The mirror of
    //     RemoteOutageGateSpec's "the first successful refresh after a close resets the interval to
    //     idle", which ends with a third probe where this one must stay at two.
    def "a clone-served success after a close keeps the grown probe interval"() {
        given:
        def probes = new AtomicInteger()
        def answer = true
        def remote = [
            probe: { Path d ->
                probes.incrementAndGet()
                answer
            },
            refresh: { Path d, String r ->
                new BaseRefreshOutcome.Refreshed('main', 'abc', BaseRefKind.COMMIT, OriginContact.CLONE_ONLY)
            },
        ] as BaseRefGit
        def grownGate = new RemoteOutageGate(remote, CLONE, clock, NO_JITTER, IDLE, Duration.ofMinutes(10))
        def git = new RemoteOutageSignalingBaseRefGit(remote, grownGate)
        grownGate.openOnFailure('boom')

        when: 'the first probe fails, growing the interval past idle'
        clock.advance(IDLE)
        answer = false
        grownGate.probeIfDue()

        then:
        grownGate.isOpen()
        probes.get() == 1

        when: 'the grown (doubled) interval elapses and a probe closes the gate'
        clock.advance(IDLE.multipliedBy(2))
        answer = true
        grownGate.probeIfDue()

        then:
        !grownGate.isOpen()
        probes.get() == 2

        when: 'time passes and the base read that follows is served by the clone alone'
        def closedAt = clock.now()
        clock.advance(Duration.ofSeconds(5))
        git.refresh(CLONE, 'main')

        then: 'it is no contact, so the remote last-contact time stays the close instant'
        grownGate.health().lastSuccessAt() == closedAt

        when: 'a new failure reopens the gate and exactly the idle interval elapses'
        grownGate.openOnFailure('boom')
        answer = false
        clock.advance(IDLE)
        grownGate.probeIfDue()

        then: 'no third probe: the reset was never spent, so the grown interval still stands'
        probes.get() == 2
        grownGate.isOpen()
    }

    // The two reads that are not claim-time base reads pass through with no gate effect: the
    //     probe IS the gate's own check, discovery precedes the gate at startup.
    def "probe and default-branch discovery pass through untouched"() {
        given:
        def discovery = new DefaultBranchDiscovery.Discovered(new DefaultBranch('main'))
        def git = signaling([
            probe: { Path d -> answer },
            discoverDefaultBranch: { Path d -> discovery },
        ] as BaseRefGit)

        expect:
        git.probe(CLONE) == answer
        git.discoverDefaultBranch(CLONE).is(discovery)
        !gate.isOpen()
        gate.health().lastSuccessAt() == null

        where:
        answer << [true, false]
    }
}
