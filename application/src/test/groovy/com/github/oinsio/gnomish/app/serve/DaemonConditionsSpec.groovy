package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.app.take.FinishedDecline
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.logging.RepeatSuppressorFixture
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * FR15, FR16, NFR-O2 of add-sandbox-base-image (design D19): the daemon's one set of conditions —
 * a typed refusal opens the condition of its kind, one gate read covers every condition, a
 * condition's probe is its own startup check and closes only its own kind, a probe that met another
 * precondition first opens that one, and a failing probe logs at DEBUG. The state machine itself
 * (open, close, schedule, three-phase probe) is pinned once, by the {@code DaemonCondition*Spec}s.
 */
class DaemonConditionsSpec extends Specification {

    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final Duration CAP = Duration.ofMinutes(10)
    private static final Duration SUSTAINED_OPEN = Duration.ofHours(1)
    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final Random NO_JITTER = new Random() {
        double nextDouble() {
            0.0
        }
    }

    private final VirtualClock clock = new VirtualClock()
    private final RepeatSuppressor suppressor = new RepeatSuppressor(clock, Duration.ofDays(1))

    /** What each kind's recheck answers now; a spec rewrites it as the operator fixes things. */
    private final Map<DaemonConditionKind, Optional<TakeResult.OperatorPreconditionUnmet>> answers =
    [:].withDefault { Optional.empty() }
    private final Map<DaemonConditionKind, AtomicInteger> rechecked = [:].withDefault {
        new AtomicInteger()
    }

    private final PreconditionRecheck rechecks = { DaemonConditionKind kind ->
        rechecked[kind].incrementAndGet()
        answers[kind]
    } as PreconditionRecheck

    private DaemonCondition condition(ConditionSubject subject) {
        new DaemonCondition(subject, clock, NO_JITTER, IDLE, CAP,
                new RemoteOutageWiring(subject.kind().name(), suppressor, SUSTAINED_OPEN, {}, RemoteOutageLedgerSink.NONE))
    }

    private DaemonConditions conditions(List<DaemonConditionKind> kinds = PRECONDITIONS) {
        new DaemonConditions(
                condition(ConditionSubject.remote(BaseRefGit.UNWIRED, Path.of('.'))),
                kinds.collect {
                    condition(ConditionSubject.precondition(it, rechecks))
                })
    }

    private static final List<DaemonConditionKind> PRECONDITIONS = [
        DaemonConditionKind.CLONE_REF_FORMAT,
        DaemonConditionKind.CONTAINER_RUNTIME,
        DaemonConditionKind.IMAGE_CONTRACT,
        DaemonConditionKind.BOX_GIT_FLOOR
    ]

    private static TakeResult.OperatorPreconditionUnmet refusal(DaemonConditionKind kind, String reason = "${kind} refused") {
        new TakeResult.OperatorPreconditionUnmet(UntrustedText.factory(reason), kind)
    }

    /** Each condition's opening WARN code, by which a spec observes which condition opened. */
    private static final Map<String, DaemonConditionKind> OPENED_BY_CODE = [
        (OperatorEvent.REMOTE_OUTAGE_GATE_OPENED.code()) : DaemonConditionKind.REMOTE,
        (OperatorEvent.CONDITION_CLONE_REF_FORMAT_OPENED.code()) : DaemonConditionKind.CLONE_REF_FORMAT,
        (OperatorEvent.CONDITION_CONTAINER_RUNTIME_OPENED.code()) : DaemonConditionKind.CONTAINER_RUNTIME,
        (OperatorEvent.CONDITION_IMAGE_CONTRACT_OPENED.code()) : DaemonConditionKind.IMAGE_CONTRACT,
        (OperatorEvent.CONDITION_BOX_GIT_FLOOR_OPENED.code()) : DaemonConditionKind.BOX_GIT_FLOOR
    ]

    private static Set<DaemonConditionKind> opened(def logs) {
        logs.list.findAll { it.level == Level.WARN }.collect { event ->
            OPENED_BY_CODE.find { code, kind ->
                event.formattedMessage.startsWith("[${code}] ")
            }?.value
        } as Set
    }

    // Scenario: serve/factory-serve / A reported refusal opens the condition of its kind
    def "FR15: a reported refusal opens the condition of its kind and no other: #kind"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition)
        def set = conditions()

        when:
        set.report(refusal(kind))

        then:
        set.anyOpen()
        opened(logs) == [kind] as Set

        cleanup:
        logs.detach()

        where:
        kind << PRECONDITIONS
    }

    // Scenario: serve/factory-serve / A precondition condition logs the fix once
    def "NFR-O2: many slots meeting one broken precondition open it once, with one WARN carrying the fix"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition)
        def set = conditions()

        when:
        3.times {
            set.report(refusal(DaemonConditionKind.CLONE_REF_FORMAT, 'clone is on files; run git refs migrate --ref-format=reftable'))
        }

        then:
        def warns = logs.list.findAll { it.level.isGreaterOrEqual(Level.WARN) }
        warns.size() == 1
        warns[0].formattedMessage.startsWith(OperatorEvent.CONDITION_CLONE_REF_FORMAT_OPENED.head())
        warns[0].formattedMessage.contains('git refs migrate --ref-format=reftable')

        cleanup:
        logs.detach()
    }

    // Scenario: serve/factory-serve / One gate read covers every condition
    // Scenario: serve/factory-serve / One claim per free slot for the whole episode
    def "FR16: an open precondition holds the feed exactly as the open remote gate does: #kind"() {
        given:
        def set = conditions()
        set.report(refusal(kind))
        def claims = new AtomicInteger()
        Tracker tracker = [claim: { TaskRef ref, String instance ->
                claims.incrementAndGet(); new ClaimResult.Acquired(new ClaimEpoch(1))
            }] as Tracker
        def ledger = new SlotLedger(1, new VirtualClock())
        ledger.acquire()

        when:
        cycle(tracker, ledger, set).claimOrAbandon([
            new ReadyTask(new TaskRef('github:o/r#1'), AbortFacts.none(), true, false, UntrustedText.tracker('t'))
        ])

        then:
        claims.get() == 0
        ledger.freeSlots() == 1

        where:
        kind << PRECONDITIONS
    }

    // Scenario: serve/factory-serve / A condition's probe is its own check
    def "FR15: the image-contract condition probes with its own check and closes once the image is rebuilt"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition, Level.DEBUG)
        def set = conditions()
        answers[DaemonConditionKind.IMAGE_CONTRACT] = Optional.of(refusal(DaemonConditionKind.IMAGE_CONTRACT, 'stale contract'))
        set.report(refusal(DaemonConditionKind.IMAGE_CONTRACT, 'stale contract'))

        when: 'the first probe is due and the image is still stale'
        clock.advance(IDLE)
        set.probeDue()

        then: 'only its own check ran, it stays open, and the failure is DEBUG only'
        rechecked.keySet() == [
            DaemonConditionKind.IMAGE_CONTRACT
        ] as Set
        rechecked[DaemonConditionKind.IMAGE_CONTRACT].get() == 1
        set.anyOpen()
        logs.list.findAll { it.level.isGreaterOrEqual(Level.WARN) }.size() == 1
        logs.list.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('still unmet')
        }

        when: 'the operator rebuilds the image on the right contract'
        answers[DaemonConditionKind.IMAGE_CONTRACT] = Optional.empty()
        clock.advance(CAP)
        set.probeDue()

        then: 'the next probe passes, the condition closes with one INFO, and the feed may claim again'
        !set.anyOpen()
        logs.list.count {
            it.level == Level.INFO && it.formattedMessage.contains('IMAGE_CONTRACT closed')
        } == 1

        cleanup:
        logs.detach()
    }

    // Scenario: serve/factory-serve / A container start is the box git floor's probe cadence
    // Scenario: serve/factory-serve / Probes repeat the condition's own check on the capped cadence
    def "FR15: an hour of an open box-git-floor condition is its own check on the growing, capped cadence"() {
        given: 'the floor recheck is the readiness assessment, one helper container per call'
        def set = conditions()
        answers[DaemonConditionKind.BOX_GIT_FLOOR] = Optional.of(refusal(DaemonConditionKind.BOX_GIT_FLOOR, 'git 2.39 below floor'))
        set.report(refusal(DaemonConditionKind.BOX_GIT_FLOOR))
        def probedAt = []

        when: 'the feed polls every idle interval for an hour'
        def end = clock.instant().plus(Duration.ofHours(1))
        while (clock.instant().isBefore(end)) {
            clock.advance(IDLE)
            int before = rechecked[DaemonConditionKind.BOX_GIT_FLOOR].get()
            set.probeDue()
            if (rechecked[DaemonConditionKind.BOX_GIT_FLOOR].get() > before) {
                probedAt << clock.instant()
            }
        }

        then: 'every probe was the floor check alone, and the intervals grow to the cap and stay there'
        rechecked.keySet() == [
            DaemonConditionKind.BOX_GIT_FLOOR
        ] as Set
        def gaps = (1..<probedAt.size()).collect {
            Duration.between(probedAt[it - 1] as Instant, probedAt[it] as Instant)
        }
        gaps.every { it <= CAP }
        gaps == gaps.sort(false)
        gaps.last() == CAP
        probedAt.size() < 120 / 2
        set.anyOpen()
    }

    def "FR15: a probe that met another precondition first opens that one and stays open itself"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition)
        def set = conditions()
        answers[DaemonConditionKind.IMAGE_CONTRACT] = Optional.of(refusal(DaemonConditionKind.CONTAINER_RUNTIME, 'runtime down'))
        set.report(refusal(DaemonConditionKind.IMAGE_CONTRACT))

        when:
        clock.advance(IDLE)
        set.probeDue()

        then:
        opened(logs) == [
            DaemonConditionKind.IMAGE_CONTRACT,
            DaemonConditionKind.CONTAINER_RUNTIME
        ] as Set

        when: 'the feed polls again at once'
        set.probeDue()

        then: 'the unjudged probe counted as failed: its next one waits for the grown interval'
        rechecked[DaemonConditionKind.IMAGE_CONTRACT].get() == 1

        when: 'the runtime returns: each probe closes its own kind'
        answers[DaemonConditionKind.IMAGE_CONTRACT] = Optional.empty()
        clock.advance(CAP)
        set.probeDue()

        then:
        !set.anyOpen()

        cleanup:
        logs.detach()
    }

    // Scenario: serve/factory-serve / A probe that cannot answer is a failed probe
    def "a probe that throws is a failed probe: nothing propagates, the schedule grows, the trace is logged once"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition, Level.DEBUG)
        def calls = new AtomicInteger()
        def throwing = new DaemonConditions(
                condition(ConditionSubject.remote(BaseRefGit.UNWIRED, Path.of('.'))),
                [
                    condition(ConditionSubject.precondition(DaemonConditionKind.CLONE_REF_FORMAT, { kind ->
                        if (calls.incrementAndGet() == 1) {
                            throw new IllegalStateException('git crashed')
                        }
                        Optional.empty()
                    } as PreconditionRecheck))
                ])
        throwing.report(refusal(DaemonConditionKind.CLONE_REF_FORMAT))
        def failedBefore = throwing.members()[1].health().consecutiveFailures()

        when: 'the due probe throws'
        clock.advance(IDLE)
        throwing.probeDue()

        then: 'the feed is not told; the condition recorded a failed probe with the failure\'s words'
        noExceptionThrown()
        throwing.anyOpen()
        with(throwing.members()[1].health()) {
            consecutiveFailures() == failedBefore + 1
            lastError().contains('IllegalStateException')
            lastError().contains('git crashed')
        }
        and: 'one DEBUG line of the streak carries the trace'
        def traced = logs.list.findAll { it.throwableProxy != null }
        traced.size() == 1
        traced[0].level == Level.DEBUG
        traced[0].throwableProxy.className == IllegalStateException.name
        traced[0].formattedMessage.contains('git crashed')

        when: 'the feed polls again at once'
        throwing.probeDue()

        then: 'the schedule grew: no second probe until the grown interval has passed'
        calls.get() == 1
        throwing.anyOpen()

        when:
        clock.advance(CAP)
        throwing.probeDue()

        then:
        calls.get() == 2
        !throwing.anyOpen()

        cleanup:
        logs.detach()
    }

    def "a probe that throws past the sustained-open threshold still earns the one ERROR"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition, Level.DEBUG)
        def throwing = new DaemonConditions(
                condition(ConditionSubject.remote(BaseRefGit.UNWIRED, Path.of('.'))),
                [
                    condition(ConditionSubject.precondition(DaemonConditionKind.IMAGE_CONTRACT, { kind ->
                        throw new IllegalStateException('No such image')
                    } as PreconditionRecheck))
                ])
        throwing.report(refusal(DaemonConditionKind.IMAGE_CONTRACT))

        when: 'the condition has been open past the threshold when its probe throws'
        clock.advance(SUSTAINED_OPEN)
        throwing.probeDue()

        then:
        def errors = logs.list.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.startsWith(OperatorEvent.CONDITION_IMAGE_CONTRACT_SUSTAINED_OPEN.head())
        errors[0].formattedMessage.contains('No such image')

        cleanup:
        logs.detach()
    }

    def "FR15: a set without a condition on a refusal's kind refuses the report"() {
        when:
        conditions([]).report(refusal(DaemonConditionKind.BOX_GIT_FLOOR))

        then:
        thrown(IllegalArgumentException)
    }

    def "FR15: a set holds one condition per kind"() {
        when:
        conditions([
            DaemonConditionKind.IMAGE_CONTRACT,
            DaemonConditionKind.IMAGE_CONTRACT
        ])

        then:
        thrown(IllegalArgumentException)
    }

    def "FR15: the production set holds the remote and the four operator preconditions"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition)
        def set = RemoteOutageGates.forServe(BaseRefGit.UNWIRED, Path.of('.'),
                new ServeProperties(0, null, null, null, null, null, null, null, null, null),
                clock, {}, { ignored -> }, rechecks)
        def fresh = !set.anyOpen()

        when:
        PRECONDITIONS.each { set.report(refusal(it)) }
        set.remote().openOnFailure('origin unreachable')

        then:
        fresh
        opened(logs) == DaemonConditionKind.values() as Set

        cleanup:
        logs.detach()
    }

    private static FeedCycle cycle(Tracker tracker, SlotLedger ledger, DaemonConditions set) {
        def outageRetry = new FeedOutageRetry(new BudgetedVirtualSleeper(new VirtualClock()), {
            Duration.ofSeconds(1)
        }, RepeatSuppressorFixture.quiet())
        def resilience = new FeedResilience(outageRetry, new FinishedDecline(RepeatSuppressorFixture.quiet()), set)
        new FeedCycle(new FeedTracker(tracker, INSTANCE), ledger, { TaskRef ref -> } as SlotRunner,
        new FeedSelection(Duration.ofMinutes(2), Duration.ofHours(1), 2, new Random(0)), new FeedStateLogger(), resilience)
    }
}
