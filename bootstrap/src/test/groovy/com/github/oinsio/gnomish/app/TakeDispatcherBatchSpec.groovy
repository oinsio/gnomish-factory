package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.lease.HeartbeatProgress
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link TakeDispatcher#runBatch}: the batch-take disposition matrix (task 6.2 of
 * add-factory-serve, FR3, FR4, design D6) — every ref runs through the same explicit-mode
 * disposition matrix as {@link TakeDispatcher#runExplicit}, {@code Working} refs are skipped
 * unless the whole-run {@code --takeover} flag authorizes every one of them, and batch never
 * consults a TTY confirmation seam regardless of what {@code take <ref>} would do.
 *
 * <p>Deliberately not built on {@link TakeResumeSpecBase}: its shared fixture stubs {@code
 * tracker.fetchTask(_)} unconditionally in {@code setup()} (an unbounded interaction that, per this
 * project's own documented Spock gotcha, wins over any later argument-specific stub in a test
 * body) — fine for that base's single-ref specs, wrong here where three distinct refs each need
 * their own {@code fetchTask} answer.
 *
 * <p>Implements FR3, FR4, D6 of add-factory-serve.
 */
@Timeout(30)
class TakeDispatcherBatchSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final TaskRef READY_REF = new TaskRef('github:acme/widgets#1')
    private static final TaskRef WORKING_REF = new TaskRef('github:acme/widgets#2')
    private static final TaskRef FINISHED_REF = new TaskRef('github:acme/widgets#3')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final int ABORT_THRESHOLD = 3

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()
    Tracker tracker = Mock()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees-root')
        tracker.listOpen() >> []
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    // The fake agent binary (plain-round: one delivering round) instead of the default `claude`:
    // the stage's AGENT_CLI executor really spawns this binary, and CI has no real claude on PATH.
    private testProps() {
        testProperties(
                instanceName: 'gnomish',
                agentCliBinary: FakeAgentSupport.propertiesFor('plain-round').agentCliBinary())
    }

    private TakeDispatcher newDispatcher(TakeoverConfirmation confirmation = TakeoverConfirmation.UNAVAILABLE) {
        new TakeDispatcher(TaskGitFixture.real(), worktreesRoot, 'taskId', testProps(), Clock.systemUTC(), [:], MapSecretsProvider.NONE, confirmation)
    }

    private static TakeHeartbeat noopHeartbeat() {
        def standingReaper =
                new StandingReaper(ReaperDuty.NONE, { Duration d -> }, Duration.ofMinutes(1), {
                    []
                }, new SystemClock())
        new TakeHeartbeat(ClaimBeat.NONE, new HeartbeatProgress(), new ClaimLossFlag(), standingReaper)
    }

    private static TrackerAdapterFactory passthroughFactory() {
        new TrackerAdapterFactory() {
                    String type() {
                        'github'
                    }

                    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }
                }
    }

    private static TrackerTask trackerTask(TaskRef ref, TrackerTaskState state, String taskId) {
        new TrackerTask(ref, new TaskSnapshot(taskId, 'title', 'body'), state, AbortFacts.none(), false)
    }

    private TakeArguments batchArgs(List<String> refs, boolean takeover = false) {
        new TakeArguments(cloneDir, refs, RunArguments.InteractiveMode.NONE, null, false, takeover)
    }

    // FR3: every ref runs through the disposition matrix (Ready delivers, Finished/Working skip),
    // results come back in the same order as the refs list, and one refusal never stops the others.
    def "runs every ref through the disposition matrix and collects outcomes in order"() {
        given: 'the Ready ref reports Working(us) once claimed, so the post-claim round-boundary revocation check sees its own claim, not a stale Ready'
        boolean readyClaimed = false
        tracker.fetchTask(READY_REF) >> {
            readyClaimed
            ? trackerTask(READY_REF, new TrackerTaskState.Working(INSTANCE.value()), 'PROJ-1')
            : trackerTask(READY_REF, new TrackerTaskState.Ready(), 'PROJ-1')
        }
        tracker.fetchTask(WORKING_REF) >> trackerTask(WORKING_REF, new TrackerTaskState.Working('someone-else'), 'PROJ-2')
        tracker.fetchTask(FINISHED_REF) >> trackerTask(FINISHED_REF, new TrackerTaskState.Finished(), 'PROJ-3')
        tracker.claim(READY_REF, INSTANCE.value()) >> {
            readyClaimed = true; new ClaimResult.Acquired()
        }
        def dispatcher = newDispatcher()
        def refs = [
            READY_REF.id(),
            WORKING_REF.id(),
            FINISHED_REF.id()
        ]

        when:
        def outcomes = dispatcher.runBatch(
                batchArgs(refs), pipeline(), new TrackerConfig('github', ABORT_THRESHOLD), tracker, INSTANCE,
                [], passthroughFactory(), newAssembly(testProps()), noopHeartbeat(), 3)

        then: 'every ref is present, in order'
        outcomes*.ref() == refs

        and: 'the Ready ref delivered'
        outcomes[0].result() instanceof TakeResult.Delivered

        and: 'the Working ref was skipped, naming the holder — no --takeover was given'
        outcomes[1].result() instanceof TakeResult.Skipped
        (outcomes[1].result() as TakeResult.Skipped).reason().contains('someone-else')

        and: 'the Finished ref was skipped as already done, and the run continued to it regardless'
        outcomes[2].result() instanceof TakeResult.Skipped
        (outcomes[2].result() as TakeResult.Skipped).reason().toLowerCase().contains('already done')
    }

    // FR4, design D6: batch is unconditionally non-interactive — a Working ref is skipped without
    // --takeover even though a TTY prompt exists in ordinary explicit mode; the invocation's own
    // takeoverConfirmation (here a Mock that would fail the test if consulted) is never asked.
    def "a Working ref is skipped without --takeover, never consulting a TTY confirmation seam"() {
        given:
        def confirmation = Mock(TakeoverConfirmation)
        tracker.fetchTask(WORKING_REF) >> trackerTask(WORKING_REF, new TrackerTaskState.Working('someone-else'), 'PROJ-2')
        def dispatcher = newDispatcher(confirmation)

        when:
        def outcomes = dispatcher.runBatch(
                batchArgs([WORKING_REF.id()]), pipeline(), new TrackerConfig('github', ABORT_THRESHOLD), tracker,
                INSTANCE, [], passthroughFactory(), newAssembly(testProps()), noopHeartbeat(), 2)

        then:
        outcomes[0].result() instanceof TakeResult.Skipped
        (outcomes[0].result() as TakeResult.Skipped).reason().contains('someone-else')
        0 * confirmation.confirm(*_)
    }

    // Design D6: batch --takeover is whole-run — the single flag on TakeArguments authorizes
    // takeover for every Working ref in the batch, proceeding past the refusal-without-the-flag
    // path straight to the ordinary claim (here it loses the race, proving it was actually
    // attempted rather than short-circuited to a skip).
    def "the whole-run --takeover flag authorizes takeover for every Working ref in the batch"() {
        given:
        def holdingId = 'gnomish-other-x1'
        tracker.fetchTask(WORKING_REF) >> trackerTask(WORKING_REF, new TrackerTaskState.Working(holdingId), 'PROJ-2')
        def dispatcher = newDispatcher()

        when:
        def outcomes = dispatcher.runBatch(
                batchArgs([WORKING_REF.id()], true), pipeline(), new TrackerConfig('github', ABORT_THRESHOLD),
                tracker, INSTANCE, [], passthroughFactory(), newAssembly(testProps()), noopHeartbeat(), 2)

        then: 'no observable claim version, so removeStaleClaim is skipped and the ordinary claim decides'
        0 * tracker.removeStaleClaim(*_)
        1 * tracker.claim(WORKING_REF, INSTANCE.value()) >> new ClaimResult.Held(holdingId)

        and: 'the ordinary claim still lost the race (Held)'
        outcomes[0].result() instanceof TakeResult.Skipped
        (outcomes[0].result() as TakeResult.Skipped).reason().contains(holdingId)
    }
}
