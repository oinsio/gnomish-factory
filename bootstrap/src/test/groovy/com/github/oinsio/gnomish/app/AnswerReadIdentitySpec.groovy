package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.DenialCursorSource
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.CommitMetadata
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Identity spec of the answer read (FR21, NFR-R5, M8 of extract-agent-round-runner; design D15, part
 * 4, and D8's row "The answer read"): the component specs prove that the live round and the recovery
 * each call the one read and the one mapping; only a flow on the real medium proves they are joined —
 * that the same bytes at a round's answer path give the same round outcome live and from a snapshot
 * another instance resumes.
 *
 * <p>Live, the gnome is a fake agent scenario that writes its {@code decision.json} to the round's
 * answer path, under the {@code claude} entry's stage half ({@link ClaudeStageFixture}) and {@link
 * SandboxRoundEnvironmentSource} over a {@link LocalBoxEnvironment}. Recovered, a round of a second
 * task world opens through the same round source, its snapshot commit carries the same bytes at its
 * answer path and lands with its register, is pushed to a bare origin atomically, and another
 * instance's fresh clone resumes it through {@link SnapshotTipCheck} and {@link
 * ResumeVerificationStageExecutor} with no agent round.
 */
class AnswerReadIdentitySpec extends Specification implements BareGitRepoFixture {

    static final String TASK = FakeAgentSupport.defaultTaskContext().taskId()
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final String REGISTER = 'refs/gnomish-factory/' + TaskIdSanitizer.sanitize(TASK)
    static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    StageExecutor.Request request

    def setup() {
        request = FakeAgentSupport.requestFor(Files.createDirectories(tempDir.resolve('workspace')))
    }

    // Scenario: executor/agent-executor / Recovery reads a snapshot's answer as the live round does
    // NFR-R5, M8: one read and one mapping, live and at recovery — a question re-raised as the live
    //     round raised it, and a refusal as the live round's failure, never a question from the text
    def "NFR-R5: the same bytes at the answer path give the same round outcome live and resumed (#row)"() {
        given: 'the bytes the scenario writes to the round\'s answer path'
        byte[] bytes = getClass().getResource("/fake-agent/scenarios/${scenario}/decision.json").bytes

        when: 'the live round writes them and is judged'
        def live = liveRound(scenario)

        and: 'a snapshot carrying them is resumed by another instance'
        def resumed = resumedRound(bytes)

        then: 'one outcome'
        resumed.status() == live.status()
        resumed.answer() == live.answer()

        and: 'the outcome the row names: a question, or a transform round\'s quality failure (FR20)'
        outcome.isInstance(live.answer() ?: live.status())
        (live.status() instanceof ExecutionResult.Failed ? (live.status() as ExecutionResult.Failed).failureClass() : null) == failure

        where:
        row | scenario || outcome | failure
        'a decision request' | 'decision-needed' || ExecutionResult.DecisionRequest | null
        'text that is not JSON' | 'decision-garbage' || ExecutionResult.Failed | ExecutionResult.FailureClass.QUALITY
    }

    /** A live round in a box of its own task world, the scenario writing its answer file. */
    private ExecutionResult liveRound(String scenario) {
        Path clone = taskWorld('live')
        def box = new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve('live-box')))
        def source = roundSource(box, clone, new CurrentRound())
        // Ahead of the fixed instant the local box stamps process starts with, so wall time is positive.
        def agent = ClaudeStageFixture.executor(FakeAgentSupport.propertiesFor(scenario),
                new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600)), ChildEnvAllowlist.none(), LAW, source)
        agent.execute(request)
    }

    /**
     * A round of a second task world opened through the round source, its snapshot carrying
     * {@code bytes} at the round's answer path, pushed to origin with its register; another
     * instance's clone resumes it.
     */
    private ExecutionResult resumedRound(byte[] bytes) {
        Path clone = taskWorld('recovered')
        def rounds = new CurrentRound()
        def box = new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve('recovered-box')))
        def round = roundSource(box, clone, rounds).openRound(request)
        snapshot(clone, round.answerFilePath().toString(), bytes, rounds)
        assert runner.run(clone, 'push', '--atomic', 'origin', "refs/heads/${BRANCH}:refs/heads/${BRANCH}",
        "${REGISTER}:${REGISTER}").exitCode() == 0

        Path pickupClone = seedClone(tempDir, clone.resolveSibling('origin.git').toString(), tempDir.resolve('pickup'))
        gitOutput(pickupClone, 'branch', BRANCH, "refs/remotes/origin/${BRANCH}")
        fetchFromOrigin(pickupClone, "${REGISTER}:${REGISTER}")
        def pending = new SnapshotTipCheck(runner, pickupClone).inspect(TASK).get()
        def noAgent = { StageExecutor.Request r ->
            throw new AssertionError('a pending verification runs no agent round')
        } as StageExecutor
        new ResumeVerificationStageExecutor(noAgent, new CurrentRound(), pending).execute(request)
    }

    /** A created task in a factory clone of its own, with a bare origin beside it ({@code origin.git}). */
    private Path taskWorld(String name) {
        Path dir = Files.createDirectories(tempDir.resolve(name))
        Path clone = initWorkingRepo(dir, 'clone')
        commit(clone, 'seed.txt', 'seed')
        // Converged on purpose: the subject is the answer read, not base resolution.
        addConvergedOrigin(clone, dir)
        new GitObjectsTaskRepository(objectsOf(clone), new VirtualClock(Instant.parse('2026-01-01T00:00:00Z')),
                ClaimEpochSource.NONE, DenialCursorSource.NONE)
                .createTask(FakeAgentSupport.defaultTaskContext(), TaskStart.commit(clone, 'HEAD'),
                TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        clone
    }

    /** The in-box snapshot of the opened round: {@code bytes} at its answer path, landed with its register. */
    private void snapshot(Path clone, String answerPath, byte[] bytes, CurrentRound rounds) {
        String ref = 'refs/heads/' + BRANCH
        def objects = objectsOf(clone)
        def tip = objects.resolveRef(ref).get()
        def identity = new CommitIdentity('gnome', 'gnome@sandbox.local')
        def now = Instant.parse('2026-01-01T00:00:00Z')
        objects.commit(new CommitRequest(ref, Optional.of(tip), tip,
                [
                    new TreeEdit.PutFile(answerPath, bytes)
                ],
                new CommitMetadata(identity, now, identity, now, ServiceCommitMessages.snapshot('build', 0, rounds.opened())))
                .alongside(REGISTER))
    }

    private SandboxRoundEnvironmentSource roundSource(LocalBoxEnvironment box, Path clone, CurrentRound rounds) {
        def lease = new EnvironmentLease({
            -> box
        }, BRANCH, [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [request.stage()])
        ])
        new SandboxRoundEnvironmentSource(lease, runner, clone, TASK, rounds, new VirtualClock())
    }

    private GitObjects objectsOf(Path clone) {
        GitObjects.open(clone.resolve('.git'), Files.createTempDirectory(tempDir, 'index'))
    }
}
