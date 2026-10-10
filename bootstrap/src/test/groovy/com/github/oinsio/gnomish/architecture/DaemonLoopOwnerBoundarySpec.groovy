package com.github.oinsio.gnomish.architecture

import static com.github.oinsio.gnomish.testsupport.ApplicationSourceScan.APP
import static com.github.oinsio.gnomish.testsupport.ApplicationSourceScan.detecting
import static com.github.oinsio.gnomish.testsupport.ApplicationSourceScan.matchesAny
import static com.github.oinsio.gnomish.testsupport.ApplicationSourceScan.spelling
import static com.github.oinsio.gnomish.testsupport.ApplicationSourceScan.unreached

import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR16, M1, M2 and design D1, D2, D5, D14 of supervise-daemon-loops-and-embed-dashboard: a
 * long-lived daemon thread has one owner, {@code SupervisedLoop}, and so do its restart policy and
 * its log roll-up period (single-owner rows 1–3). The dashboard's own owners (D10, D11) are gated
 * beside it, by {@link DashboardOwnerBoundarySpec}.
 *
 * <p>The defect this gate exists for had a green build: every daemon loop started its own virtual
 * thread, ran its own {@code while} and caught its own failures, so one of them could die silently
 * while every unit spec of every loop stayed green. A new loop written the old way is outside every
 * allowlist below by default. The scope is {@code application/src/main}, the layer holding every
 * daemon loop; subprocess and stream pumps in other modules are finite threads. The {@link
 * ClaimlessGitBoundarySpec} shape: paths, comments stripped, and every allowlisted
 * file asserted reached so a moved or stale exemption fails instead of widening the gate.
 */
class DaemonLoopOwnerBoundarySpec extends Specification {

    /**
     * Thread creation: the shape a daemon loop of its own starts with. Detectors, not substrings, so
     * a static import ({@code ofVirtual(}), a constructor reference ({@code Thread::new}), the
     * one-call start and every executor factory or pool constructor are thread creation too.
     */
    private static final List<Pattern> THREAD_CREATION = [
        ~/\bof(Virtual|Platform)\s*\(/,
        ~/\bnew\s+Thread\s*\(/,
        ~/\bThread\s*::\s*new\b/,
        ~/\bstartVirtualThread\s*\(/,
        ~/\bExecutors\s*\.\s*new/,
        ~/\bnew\w*Thread\w*\s*\(/,
        ~/\bnew\s+(Scheduled)?ThreadPoolExecutor\s*\(/,
        ~/\bnew\s+ForkJoinPool\s*\(/,
    ]

    /** The files allowed to create a thread, each with the reason it may (row 1). */
    private static final Map<String, String> THREAD_OWNERS = [
        (APP + 'app/daemon/SupervisedLoop.java'): 'the owner: the daemon thread, its guard, stop and restart (D1-D5)',
        (APP + 'app/lease/HeldClaims.java'): 'the heartbeat worker, not resurrected by D3 of add-claim-heartbeat',
        (APP + 'app/serve/FeedCycle.java'): 'one finite thread per claimed task',
        (APP + 'app/TakeBatch.java'): 'a finite batch of take runs',
        (APP + 'app/ServeShutdownWiring.java'): 'the feed thread owns the daemon lifetime; JVM shutdown hooks',
    ]

    /** A scheduler: the silent-death shape D1 rejects, since a throwing task cancels its own schedule. */
    private static final List<Pattern> SCHEDULERS = [
        ~/\bExecutors\s*\.\s*new(SingleThread)?Scheduled/,
        ~/\bnew(SingleThread)?Scheduled\w*\s*\(/,
        ~/\bScheduledExecutorService\b/,
        ~/\bnew\s+ScheduledThreadPoolExecutor\s*\(/,
        ~/\bnew\s+Timer\s*\(/,
    ]

    /** Row 2 (D5): the restart backoff is built only by the policy and by the probe schedule. */
    private static final Set<String> BACKOFF_OWNERS = [
        APP + 'app/daemon/RestartPolicy.java',
        APP + 'app/serve/RemoteOutageProbeSchedule.java',
    ] as Set

    /**
     * Row 3 (D2): the loop files and the heartbeat timing never build a suppressor of their own —
     * the loop builds it from its clock. The dashboard loop is {@code app/DashboardWatch.java}
     * (task 8.1).
     */
    private static final List<String> NO_OWN_SUPPRESSOR = [
        APP + 'app/lease/StandingReaper.java',
        APP + 'app/serve/WorktreeJanitor.java',
        APP + 'app/serve/SandboxLifecycleTick.java',
        APP + 'serveobservability/writer/SnapshotWriter.java',
        APP + 'app/DashboardWatch.java',
        APP + 'app/lease/BeatTiming.java',
    ]

    /** Split at the parenthesis: the test-time gate reads any {@code .system(} in a test source as real time. */
    private static final List<String> OWN_SUPPRESSOR = [
        'RepeatSuppressor.system' + '(',
        'new RepeatSuppressor('
    ]

    /** Row 3 (D2): the catalog default is read only where the period is derived (bare, so a static import is caught too). */
    private static final String ROLL_UP_DEFAULT = 'DEFAULT_ROLL_UP_INTERVAL'
    private static final Set<String> ROLL_UP_OWNERS = [
        APP + 'app/daemon/RollUpPeriod.java'
    ] as Set

    // FR16, M1, D1: a thread started outside the owner is a daemon loop nobody supervises.
    def "FR16, M1: only the supervised loop and the four finite-thread owners create a thread"() {
        expect: 'the scan reached every allowlisted file, each still creates a thread, and no other file does'
        detecting(THREAD_CREATION) == THREAD_OWNERS.keySet()
    }

    // FR16, D1: a scheduled task that throws is cancelled without a trace — the silent death.
    def "FR16, D1: no scheduler matches #token in application"() {
        expect: 'the scan reached the tree'
        unreached(THREAD_OWNERS.keySet()).isEmpty()

        and: 'no file matches it'
        detecting([token]).isEmpty()

        where:
        token << SCHEDULERS
    }

    // FR16, M2, D5: a second construction site of the backoff is a second restart policy.
    def "FR16, M2, D5: the restart backoff is built only by its two owners"() {
        expect:
        spelling(['new RestartBackoff(']) == BACKOFF_OWNERS
    }

    // D2: a loop holding its own suppressor rolls up on a period nobody derived from its interval.
    def "D2: no loop file and no heartbeat timing builds a suppressor of its own"() {
        expect: 'the scan reached every listed file'
        unreached(NO_OWN_SUPPRESSOR).isEmpty()

        and: 'none of them builds one'
        (spelling(OWN_SUPPRESSOR) intersect NO_OWN_SUPPRESSOR).isEmpty()
    }

    // D2: the roll-up period has one producer; a second reader of the default is a second period.
    def "D2: the roll-up default is read only by the roll-up period"() {
        expect:
        spelling([ROLL_UP_DEFAULT]) == ROLL_UP_OWNERS
    }

    // Over a clean tree the scan finds only the owners, so only seeded sources tell a working
    //     detector from one a new spelling walks past.
    def "FR16, D1: the detector finds a seeded thread or scheduler: #shape"() {
        expect:
        startsThreads(source)

        where:
        shape | source
        'a virtual builder' | 'Thread.ofVirtual().start(task);'
        'a statically imported builder' | 'ofVirtual().start(task);'
        'a platform builder' | 'Thread.ofPlatform().start(task);'
        'a thread constructor' | 'new Thread(task).start();'
        'a thread constructor reference'| 'factory(Thread::new);'
        'the one-call virtual start' | 'Thread.startVirtualThread(task);'
        'an executor factory' | 'var pool = Executors.newVirtualThreadPerTaskExecutor();'
        'a fixed pool' | 'var pool = Executors.newFixedThreadPool(2);'
        'a scheduled pool constructor' | 'var pool = new ScheduledThreadPoolExecutor(1);'
        'a pool constructor' | 'var pool = new ThreadPoolExecutor(1, 1, 0, SECONDS, queue);'
        'a scheduler type' | 'ScheduledExecutorService scheduler;'
        'a timer' | 'var timer = new Timer();'
    }

    def "FR16, D1: the detector leaves look-alikes alone: #shape"() {
        expect:
        !startsThreads(source)

        where:
        shape | source
        'the current thread' | 'Thread.currentThread().interrupt();'
        'a name containing Thread' | 'var t = new ThreadSleeper();'
        'a join' | 'worker.join();'
    }

    private static boolean startsThreads(String code) {
        matchesAny(code, THREAD_CREATION + SCHEDULERS)
    }
}
