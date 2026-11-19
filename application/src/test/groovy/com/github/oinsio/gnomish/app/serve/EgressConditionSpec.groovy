package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.EgressProbe
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.ServePreconditions
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * FR18, NFR-R5 of migrate-verify-to-executor-contract, design D16 ("Where a refusal goes, per
 * layer"; ADR 0019 d.3, d.5): a tracker call the egress guard refuses is the daemon's operator
 * precondition {@code EGRESS}. The feed's outage retry hands it to the conditions instead of
 * retrying it; the condition's probe is the startup owner's own {@code EGRESS} check — the tracker
 * endpoint's connect-time judgement, no request sent — and closes the condition when the grant
 * holds again.
 */
class EgressConditionSpec extends Specification {

    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final AdmittedEndpoint ENDPOINT = new AdmittedEndpoint(
    URI.create('https://gitea.internal/api/v1'), 'tracker.github.api-url', ['private'])
    private static final Random NO_JITTER = new Random() {
        double nextDouble() {
            0.0
        }
    }

    private final VirtualClock clock = new VirtualClock()
    private final RepeatSuppressor suppressor = new RepeatSuppressor(clock, Duration.ofDays(1))

    /** Whether the endpoint's host currently resolves into an address its admitted set grants. */
    private boolean granted = false
    private final AtomicInteger judged = new AtomicInteger()

    private final EgressProbe probe = { AdmittedEndpoint endpoint ->
        judged.incrementAndGet()
        if (!granted) {
            throw refusal()
        }
    } as EgressProbe

    private static EgressRefusedException refusal() {
        new EgressRefusedException(new EgressRefusal(EgressRefusal.Reason.NOT_ALLOWLISTED,
                ENDPOINT.url().toString(), "host 'gitea.internal' resolves to 93.184.216.34", ENDPOINT.key()))
    }

    private DaemonConditions conditions() {
        // Only the EGRESS arm is reached: the other collaborators of the startup owner stay unset.
        def owner = new ServePreconditions(null, null, null, null, probe)
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [],
        new TrackerConfig('github', 3).withEndpoint(ENDPOINT))
        def rechecks = owner.rechecks(
                RegisteredCloneFixture.unregistered(Path.of('/srv/gnomish'), Path.of('/src/widgets')), definition)
        def wiring = { kind ->
            new RemoteOutageWiring(kind.name(), suppressor, Duration.ofHours(1), {}, RemoteOutageLedgerSink.NONE)
        }
        new DaemonConditions(
                new DaemonCondition(ConditionSubject.remote(BaseRefGit.UNWIRED, Path.of('.')), clock, NO_JITTER, IDLE,
                Duration.ofMinutes(10), wiring(DaemonConditionKind.REMOTE)),
                [
                    new DaemonCondition(ConditionSubject.precondition(DaemonConditionKind.EGRESS, rechecks), clock, NO_JITTER,
                    IDLE, Duration.ofMinutes(10), wiring(DaemonConditionKind.EGRESS))
                ])
    }

    private FeedOutageRetry retry() {
        new FeedOutageRetry({ Duration pause ->
            clock.advance(pause)
        } as Sleeper, {
            IDLE
        }, suppressor)
    }

    // FR18: a refusal of the feed's poll is not an outage to retry but the daemon's condition
    // Scenario: serve/factory-serve / The feed stops on a refusal instead of retrying it
    def "a refused poll is not retried, opens the EGRESS condition naming the key, and holds the feed"() {
        given:
        def set = conditions()
        def polls = new AtomicInteger()

        when:
        def read = retry().runHolding('feed poll', {
            polls.incrementAndGet()
            throw refusal()
        }, set.&reportRefusal, 'held')

        then: 'one attempt, the held answer, and the condition open'
        polls.get() == 1
        read == 'held'
        set.anyOpen()
        set.openPreconditions() == [DaemonConditionKind.EGRESS] as Set
        set.members().find { it.kind() == DaemonConditionKind.EGRESS }.isOpen()
    }

    def "a failure no precondition classifies is still the outage the retry rides out"() {
        given:
        def attempts = new AtomicInteger()

        when:
        def read = retry().runHolding('feed poll', {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException('tracker unavailable')
            }
            'read'
        }, { throw new AssertionError('not a refusal') }, 'held')

        then:
        read == 'read'
        attempts.get() == 3
    }

    def "reportRefusal rethrows a failure that names no precondition"() {
        given:
        def failure = new IllegalStateException('not a refusal')

        when:
        conditions().reportRefusal(failure)

        then:
        def rethrown = thrown(IllegalStateException)
        rethrown.is(failure)
    }

    // FR18, ADR 0019 d.3: the probe is the condition's own check, sending no request
    // Scenario: serve/factory-serve / The probe closes the condition when the grant holds again
    def "the probe repeats the endpoint's judgement, keeps the condition open while refused, and closes it once granted"() {
        given:
        def set = conditions()
        set.reportRefusal(refusal())

        when: 'a probe while the host still resolves outside the grant'
        clock.advance(IDLE)
        set.probeDue()

        then:
        judged.get() == 1
        set.anyOpen()

        when: 'the host resolves into an admitted address again'
        granted = true
        clock.advance(IDLE.multipliedBy(4))
        set.probeDue()

        then: 'the condition closes, and the feed may claim again'
        judged.get() == 2
        !set.anyOpen()
    }
}
