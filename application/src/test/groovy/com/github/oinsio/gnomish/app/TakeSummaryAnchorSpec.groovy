package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.status.AnchorLog
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR3, D3 of harden-logging-observability: {@code take}'s end of the canonical task summary,
 * asserted where it is actually emitted — the dispatcher's own outermost boundary, which is the
 * last point that still holds the task's {@code taskId} MDC. Both entry points reach it: the
 * explicit one-ref mode and the bare queue walk.
 *
 * <p>The run is real from the dispatcher down — real disposition, real fresh claim, a real engine
 * over the domain's scripted port fakes — with git and tracker scripted at the edges, the same
 * port-fake shape {@code TakeFreshClaimSpec} uses. That matters here: a summary is assembled from
 * a terminal result, so nothing short of a run that actually reaches one can prove the line is
 * emitted.
 *
 * <p>Implements FR3 of harden-logging-observability.
 */
@Timeout(10)
class TakeSummaryAnchorSpec extends Specification implements RunChainFakes {

    private static final Logger LOG = LoggerFactory.getLogger(TakeSummaryAnchorSpec)
    private static final TrackerConfig TRACKER_CONFIG = new TrackerConfig('github', 3)
    private static final ServeProperties SERVE_PROPERTIES = new ServeProperties(
    1, Duration.ofMillis(1), null, null, null, null, null)

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    LogCaptureSupport capture
    Tracker tracker = Mock(Tracker)

    def setup() {
        cloneDir = tempDir.resolve('gnomish-clone')
        worktreesRoot = tempDir.resolve('worktrees')
        Files.createDirectories(cloneDir)
        // Stands in for the `git worktree add` the real repository would have performed —
        // DirectoryWorkspace refuses a path that is not an existing directory.
        Files.createDirectories(TaskWorktreePath.resolve(worktreesRoot, cloneDir, 'PROJ-1'))
        capture = LogCaptureSupport.attach(AnchorLog)
    }

    def cleanup() {
        capture.detach()
    }

    private TakeDispatcher dispatcher() {
        def store = Stub(TaskStoreGit) {
            taskRepository(_, _) >> Stub(TaskLifecycleStore)
            attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
            readTaskRecord(_) >> freshRecord()
        }
        def branches = Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
            classifyShape(_, _) >> new BranchShape.Bare()
        }
        new TakeDispatcher(
                new TaskGit(store, branches, Stub(TaskWorktreeGit)), worktreesRoot, 'taskId', testProperties(),
                FIXED_CLOCK, ['github': Stub(TrackerAdapterFactory)], MapSecretsProvider.NONE,
                TakeoverConfirmation.UNAVAILABLE, ContainerTakeSupport.hostOnly(), new ClaimEpochBook())
    }

    private void dispatch(List<String> refs) {
        def heartbeat = TakeHeartbeat.forRun(tracker, TRACKER_CONFIG, { Duration d -> } as Sleeper)
        TakeRefDispatch.run(dispatcher(),
                new TakeArguments(cloneDir, refs, RunArguments.InteractiveMode.NONE, null, false, false),
                completingPipeline(), TRACKER_CONFIG, tracker, INSTANCE, [], Stub(TrackerAdapterFactory),
                assemblyRunning(new ScriptedExecutor([completedRound()])), heartbeat, SERVE_PROPERTIES, LOG)
    }

    /** The rendered {@code wall=} field of the one captured summary line. */
    private Duration capturedWall() {
        def matcher = capture.list[0].formattedMessage =~ /wall=([^,]+)/
        assert matcher.find()
        Duration.parse(matcher.group(1))
    }

    // FR3: a `take <ref>` that delivers ends in exactly one summary line — the same line, from the
    // same renderer, a serve slot writes.
    def "an explicit take that delivers ends in one canonical summary line"() {
        given:
        tracker.fetchTask(REF) >>> [
            readyTask(),
            heldByUs(),
            heldByUs()
        ]
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))

        when:
        dispatch(['github:o/r#1'])

        then:
        thrown(TakeExitCodeException)

        and: 'one summary, naming the delivery'
        capture.list.size() == 1
        capture.list[0].level == Level.INFO
        capture.list[0].formattedMessage.startsWith('task summary: outcome=delivered')
    }

    // FR3: the bare queue walk reaches the same boundary — a task claimed off the queue is
    // summarized exactly like one named on the command line, which is what "one form for all
    // modes" has to mean inside a single mode too.
    def "a bare take that delivers ends in the same summary line"() {
        given:
        tracker.listReady(_) >> [
            new ReadyTask(REF, AbortFacts.none(), true, false, 'title')
        ]
        tracker.listOpen() >> []
        tracker.fetchTask(REF) >>> [
            readyTask(),
            heldByUs(),
            heldByUs()
        ]
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))

        when:
        dispatch([])

        then:
        thrown(TakeExitCodeException)

        and: 'the walk opens with its own claim anchor and closes with the one summary (FR2, FR3)'
        def anchors = capture.list*.formattedMessage
        anchors.first().startsWith("claim acquired for task ${REF.id()}")
        anchors.findAll { it.startsWith('task summary:') } ==
        [anchors.last()]
        anchors.last().startsWith('task summary: outcome=delivered')
    }

    // FR3: the wall time is the run's own elapsed time — the difference between two monotonic
    // readings, never a raw one. A summary reporting the clock's origin would hand the operator a
    // duration that looks like a fact and is off by however long the machine has been up.
    def "the summary's wall time is the run's elapsed time, not a raw monotonic reading"() {
        given:
        tracker.fetchTask(REF) >>> [
            readyTask(),
            heldByUs(),
            heldByUs()
        ]
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))

        when:
        dispatch(['github:o/r#1'])

        then:
        thrown(TakeExitCodeException)

        and:
        !capturedWall().negative
        capturedWall() <Duration.ofMinutes(1)
    }
}
