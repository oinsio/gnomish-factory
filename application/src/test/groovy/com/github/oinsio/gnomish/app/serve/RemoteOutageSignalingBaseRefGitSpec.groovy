package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import java.time.Duration
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
        def outage = new BaseRefreshOutcome.Unavailable('connection refused')
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

    // FR14: a successful refresh is the "successful base refresh" signal, stamped at the read's
    //     own instant — the fact's time, not the slot's end.
    def "a successful refresh confirms the refresh at the read's instant and is forwarded"() {
        given:
        def refreshed = new BaseRefreshOutcome.Refreshed('main', 'abc', BaseRefKind.BRANCH)
        def git = signaling([refresh: { Path d, String r ->
                refreshed
            }] as BaseRefGit)
        clock.advance(Duration.ofMinutes(5))

        when:
        def outcome = git.refresh(CLONE, 'main')

        then:
        outcome.is(refreshed)
        !gate.isOpen()
        gate.health().lastSuccessAt() == clock.now()
    }

    // FR14: a refusal means origin answered but no base was refreshed — neither signal fires.
    def "a refused refresh signals nothing and is forwarded"() {
        given:
        def refused = new BaseRefreshOutcome.Refused('no such ref')
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

    // FR14, D13: the resume rebind is the other claim-time base read — same three arms.
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
        new ResumeBaseOutcome.Unavailable('timed out') | true | false
        new ResumeBaseOutcome.Bound('main', 'abc') | false | true
        new ResumeBaseOutcome.Refused('gone') | false | false
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
