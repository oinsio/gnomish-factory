package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.time.Duration
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.Timeout

/**
 * FR9, FR10 of add-tracker-port and FR2, FR3 (design D2) of add-factory-serve: how {@code gnomish
 * take} decides WHICH mode it is in, from the ref list alone. Three modes, three different exit
 * behaviours — bare (no refs) walks the queue for one task, explicit (one ref) disposes of that
 * task, and batch (two or more) runs them all and aggregates. Batch also logs its checklist summary
 * BEFORE throwing the aggregate exit code, so the summary is visible however the caller handles it.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules): the dispatcher is real and
 * the tracker is scripted, so which mode ran is observable from which tracker call it made.
 *
 * <p>Added by task 8.7 of split-into-modules.
 *
 * <p>Class-level {@code @Timeout}: the batch features drive the real {@code TakeBatch} loop over a
 * slot ledger, so a wait of seconds means a permit was never returned — a deadlock, which is
 * precisely what a batch spec should report. Without the bound, the mutants that delete {@code
 * SlotLedger.assign} or {@code release} park this spec forever and PIT reports TIMED_OUT instead
 * of KILLED (task 10.3 of split-into-modules; {@code TakeBatchSpec} carries the same bound for
 * the same reason). Far above any real run, far below PIT's per-mutation budget.
 */
@Timeout(10)
class TakeRefDispatchSpec extends Specification implements RunChainFakes {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(TakeRefDispatchSpec)
    private static final TrackerConfig TRACKER_CONFIG = new TrackerConfig('github', 3)
    private static final ServeProperties SERVE_PROPERTIES = new ServeProperties(
    2, Duration.ofMillis(50), Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofSeconds(5), 14)

    Tracker tracker = Mock(Tracker)
    TrackerAdapterFactory factory = Stub(TrackerAdapterFactory)

    /** Captures what the dispatch logged, so the batch summary's presence is assertable. */
    ListAppender<ILoggingEvent> logged = new ListAppender<>()

    def setup() {
        // The summary is written through the logger the dispatch is HANDED, not one of its own.
        def summaryLogger = (Logger) LOG
        logged.start()
        summaryLogger.addAppender(logged)
    }

    private TakeDispatcher dispatcher() {
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
        }, Stub(TaskWorktreeGit))
        new TakeDispatcher(git, WORKTREES_ROOT, 'taskId', testProperties(), FIXED_CLOCK,
                ['github': Stub(TrackerAdapterFactory)], MapSecretsProvider.NONE, TakeoverConfirmation.UNAVAILABLE)
    }

    private void dispatch(List<String> refs) {
        def heartbeat = TakeHeartbeat.forRun(tracker, TRACKER_CONFIG, { Duration d -> } as Sleeper)
        TakeRefDispatch.run(dispatcher(),
                new TakeArguments(CLONE_DIR, refs, RunArguments.InteractiveMode.NONE, null, false, false),
                pipeline(), TRACKER_CONFIG, tracker, INSTANCE, [], factory,
                assemblyRunning(null), heartbeat, SERVE_PROPERTIES, LOG)
    }

    // FR10: no refs means BARE mode — the queue is read and one task is walked for. An empty queue
    // is the idle answer, and it still exits through the take exit-code protocol.
    def "no refs walks the ready queue in bare mode"() {
        when:
        dispatch([])

        then:
        1 * tracker.listReady(_) >> []
        1 * tracker.listOpen() >> []
        0 * tracker.fetchTask(_)

        and:
        thrown(TakeExitCodeException)
    }

    // FR9: one ref means EXPLICIT mode — that exact task is fetched and disposed of, with no queue
    // read at all, because the operator already said which task they mean.
    def "one ref disposes of exactly that task, without reading the queue"() {
        when:
        dispatch(['github:o/r#1'])

        then:
        1 * tracker.fetchTask(REF) >> readyTask()
        1 * tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Held('someone-else')
        0 * tracker.listReady(_)

        and:
        thrown(TakeExitCodeException)
    }

    // FR2, FR3, D2: two or more refs means BATCH mode — every ref goes through the disposition
    // matrix and the run exits with the AGGREGATE code, not the first ref's.
    def "two or more refs run every ref and exit with the aggregate code"() {
        when:
        dispatch([
            'github:o/r#1',
            'github:o/r#2'
        ])

        then: 'both refs were disposed of — neither was dropped'
        1 * tracker.fetchTask(REF) >> readyTask()
        1 * tracker.fetchTask(new TaskRef('github:o/r#2')) >> readyTask('PROJ-2')
        2 * tracker.claim(_, _) >> new ClaimResult.Held('someone-else')

        and: 'the exit code aggregates the real outcomes — both were skipped, so 15, not 0'
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15

        and: 'FR3, NFR-O2, UX3: the checklist summary is logged BEFORE the exit code is thrown'
        logged.list.any {
            it.formattedMessage.contains('PROJ-1') || it.formattedMessage.contains('o/r#1')
        }
    }

    // FR9, design D8: a canonical ref naming a repo the adapter cannot reconcile to the configured
    // binding is refused BEFORE the task is ever fetched — a foreign repo is never touched.
    def "refuses a foreign canonical ref before fetching the task"() {
        given:
        factory = Stub(TrackerAdapterFactory) {
            refuseForeignRef(_, _, _) >> Optional.of('ref names another repository')
        }

        when:
        dispatch(['github:other/repo#7'])

        then:
        0 * tracker.fetchTask(_)
        0 * tracker.claim(_, _)

        and:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15
    }

    // FR14, D16 "Runner crash is an abort": the dispatcher builds the abort handler the claim chain
    // funnels a crashed run into, over the run's OWN tracker — so a crash is recorded, not lost.
    def "builds the abort handler a crashed run is recorded through"() {
        when:
        dispatch(['github:o/r#1'])

        then:
        2 * tracker.fetchTask(REF) >> readyTask()
        1 * tracker.claim(REF, _) >> new ClaimResult.Acquired()

        and: 'the run crashes inside the claim; the abort re-reads the facts and lands on this tracker'
        1 * tracker.recordAbort(REF, _)

        and:
        thrown(TakeExitCodeException)
    }

    // D2, FR3: the boundary between explicit and batch is exactly two refs — one ref must not take
    // the batch path (it would aggregate a single outcome and log a checklist for one line).
    def "treats exactly one ref as explicit and two as batch"() {
        when:
        dispatch(refs)

        then:
        callsToFetch * tracker.fetchTask(_) >> readyTask()
        tracker.claim(_, _) >> new ClaimResult.Held('someone-else')
        thrown(TakeExitCodeException)

        where:
        refs || callsToFetch
        ['github:o/r#1'] || 1
        [
            'github:o/r#1',
            'github:o/r#2'
        ] || 2
        [
            'github:o/r#1',
            'github:o/r#2',
            'github:o/r#3'
        ] || 3
    }
}
