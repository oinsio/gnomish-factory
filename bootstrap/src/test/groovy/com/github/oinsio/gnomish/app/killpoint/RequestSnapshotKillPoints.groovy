package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.git.RoundTokenRef
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.CommitMetadata
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * The round-token kill window as a table row (FR15, NFR-R4 of make-checkpoint-gate-durable, design
 * D10), container medium: a round that asked lands 1. its snapshot commit — the request at the
 * token path, the token in the subject — and 2. its state commit. A kill after 1 freezes the
 * {@code PendingVerification} sub-state (a tip whose subject is a snapshot); its recovery owner is
 * {@link ResumeVerificationStageExecutor}, which re-raises the request {@link SnapshotTipCheck}
 * read from the snapshot's tree — roll forward, no agent round, no attempt burned. The state
 * commit after it is D3's window (the stop on the record), owned by the gate rows, so this row has
 * the one kill point.
 *
 * <p>The pickup is the resume path's own pieces, not a re-implementation: the token is minted by
 * {@link SandboxRoundEnvironmentSource#openRound}, the snapshot read by {@link SnapshotTipCheck},
 * the request mapped by {@link ResumeVerificationStageExecutor}; the escalation it records is the
 * engine's park outcome, whose tracker delivery {@link ParkKillPoints} owns. The epilogue answers
 * the question and opens the next round through the same owner: it is handed a path under its own
 * token, and reads nothing, though the answered request may still be on the tip.
 */
final class RequestSnapshotKillPoints {

    static final String STAGE = 'build'

    static final String REQUEST = '{"question":"which db?","options":["pg","sqlite"]}'

    static final String REPLY = 'pg'

    private RequestSnapshotKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it
     * @param world builds a freshly created, claimed task in that medium
     */
    static KillPointTransition transition(String medium, Closure world) {
        // Agent rounds the pickup ran; reset with the world, so no window inherits another's tally.
        def agentRounds = new AtomicInteger()
        new KillPointTransition(
                name: "${medium} decision request in a snapshot",
                steps: [
                    'the snapshot commit carrying the request'
                ],
                world: {
                    agentRounds.set(0)
                    world.call()
                },
                step: { KillPointWorld w, int index -> snapshotAsking(w) },
                shape: { KillPointWorld w -> w.shape() },
                pickup: { KillPointWorld w -> pickup(w, agentRounds) },
                fingerprint: { KillPointWorld w -> w.fingerprint() },
                invariant: { KillPointWorld w ->
                    assert agentRounds.get() == 0: 'the pickup replayed the round instead of re-raising its request'
                    assert w.tipTask().lastEscalation().toString().contains('which db?')
                },
                epilogue: { KillPointWorld w -> answerThenNextRound(w) },
                frozenShapes: ['Created'],
                converged: 'Parked')
    }

    /** The round opens through the token's one owner, the gnome asks, the in-box snapshot lands it. */
    private static void snapshotAsking(KillPointWorld world) {
        def tokens = new RoundTokenRef()
        def round = roundSource(world, tokens).openRound(request(world, 0))
        String ref = 'refs/heads/' + TaskIdSanitizer.branchName(world.taskId)
        def objects = GitObjects.open(world.repoDir, Files.createDirectories(world.repoDir.resolveSibling('snapshot-index')))
        def tip = objects.resolveRef(ref).get()
        def identity = new CommitIdentity('gnome', 'gnome@sandbox.local')
        def now = Instant.now()
        objects.commit(new CommitRequest(ref, Optional.of(tip), tip,
                [
                    new TreeEdit.PutFile(round.decisionFilePath().toString(), REQUEST.getBytes(StandardCharsets.UTF_8))
                ],
                new CommitMetadata(identity, now, identity, now, ServiceCommitMessages.snapshot(STAGE, 0, tokens.required()))))
    }

    /**
     * The resume path's interrupted-verification arm: a snapshot tip is re-verified without an agent
     * round, and a request it carries is re-raised and parked as the engine parks a {@code
     * DecisionNeeded}. Any other tip — the parked one included — is not this owner's, so a second
     * pass does nothing.
     */
    private static void pickup(KillPointWorld world, AtomicInteger agentRounds) {
        def pending = new SnapshotTipCheck(world.runner, world.repoDir).inspect(world.taskId)
        if (pending.isEmpty()) {
            return
        }
        def agent = { StageExecutor.Request r ->
            agentRounds.incrementAndGet()
            throw new AssertionError('no agent round may run for a pending verification')
        } as StageExecutor
        def result = new ResumeVerificationStageExecutor(agent, new AttemptCommitRef(), pending.get())
                .execute(request(world, pending.get().round()))
        if (result instanceof ExecutionResult.DecisionNeeded) {
            world.store.recordOutcome(world.taskId, new TaskOutcome.Escalated(TaskState.atStageStart(STAGE),
                    new EscalationReport.DecisionNeeded(result.question(), result.options())), TrackerWrite.OWED)
        }
    }

    /**
     * The answer restarts the stage at attempt 0 — the same key as the asking round — and the next
     * round, opened by the owner on the answer's tip, reads exactly its own token path: nothing.
     */
    private static void answerThenNextRound(KillPointWorld world) {
        world.store.appendDecision(world.taskId, new Decision(REPLY, null, null, null), TaskState.atStageStart(STAGE))
        def tokens = new RoundTokenRef()
        def next = roundSource(world, tokens).openRound(request(world, 0))
        assert next.readDecision().isEmpty(): 'the next round read the answered request'
        assert next.decisionFilePath().toString().endsWith("-${tokens.required().commit()}.json")
    }

    private static SandboxRoundEnvironmentSource roundSource(KillPointWorld world, RoundTokenRef tokens) {
        def lease = new EnvironmentLease({
            -> new TipTreeEnvironment(world.repoDir)
        },
        TaskIdSanitizer.branchName(world.taskId),
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ])
        new SandboxRoundEnvironmentSource(
                lease, world.runner, world.repoDir, world.taskId, new AttemptCommitRef(), tokens, new VirtualClock())
    }

    private static StageExecutor.Request request(KillPointWorld world, int attempt) {
        new StageExecutor.Request(
                new TaskContext(world.taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                stage(), new DirectoryWorkspace(world.repoDir.parent), attempt, [])
    }

    private static StageDefinition stage() {
        new StageDefinition(STAGE, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }
}
