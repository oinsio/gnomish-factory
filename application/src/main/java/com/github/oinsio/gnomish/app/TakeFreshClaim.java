package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.git.TaskWorktreePath;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TrackerTaskSynthesizer;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;

/**
 * The "no branch exists yet" half of {@link TakeDisposition}'s {@code Ready} case (FR9, FR11,
 * D3): synthesizes the initial {@link com.github.oinsio.gnomish.domain.engine.TaskContext}/{@link
 * com.github.oinsio.gnomish.domain.engine.TaskState} pair from the already-fetched {@link
 * TrackerTask}'s snapshot (FR11's "snapshot at first claim" — the snapshot was already fetched by
 * the caller as part of {@code fetchTask}; this class never re-fetches it), creates the task
 * branch/worktree via {@link GitFreshTaskSupport#createTask} (mirroring {@link GitModeRunner}'s
 * fresh-run sequence), reads back {@code task.json} to build a {@link ResumeBootstrap}, then runs
 * the engine once through {@link TakeEngineExecution} — the same execution tail resume uses,
 * since it only reads {@code worktreePath}/{@code taskId}/{@code branchName} off the bootstrap and
 * never assumes the branch pre-existed.
 *
 * <p>The base the task branches from is resolved and freshly refreshed via {@link
 * FreshClaimBaseBinding} (FR2, FR6, D6, D15 of add-base-ref-resolution) before anything durable is
 * created; the definition the task runs under is then read from THAT resolved commit's law
 * binding ({@link TaskTierLaw}, FR13), never from the startup definition the caller holds. A base
 * that cannot be resolved or refreshed parks or releases the claim there — see {@link
 * FreshClaimBaseBinding}'s javadoc for the classification.
 *
 * <p>An instance owns the host fresh-claim recipe over the slot's {@link SlotWiring}, which it
 * holds as a field for the slot's lifetime: each claim receives only its {@link TakeOrder}, never
 * the equipment (D2 of introduce-slot-wiring).
 *
 * <p>Kept in sync with {@link TakeContainerFreshClaim}: both run the SAME fresh-claim recipe —
 * harden, resolve+refresh the base, bind the task tier at that base, synthesize, create the
 * branch FROM THE BOUND LAW COMMIT with the base pin beside it (FR15, D12 revised 2026-09-10),
 * run the engine once — over their own execution medium (host worktree vs. sandbox task
 * repository). Both hand on, past the task-tier bind, only the order re-bound to the task's law
 * through {@link TakeOrder#withDefinition} (D6 of introduce-take-order).
 *
 * <p>Implements FR9, FR11, D3 of add-tracker-port; FR2, FR6, FR13, D6, D15 of
 * add-base-ref-resolution; FR5 of introduce-slot-wiring.
 */
final class TakeFreshClaim {

    private final SlotWiring wiring;

    TakeFreshClaim(SlotWiring wiring) {
        this.wiring = wiring;
    }

    /**
     * Creates the task branch/worktree for a first claim and runs the engine once (see class
     * javadoc).
     *
     * <p>Implements FR9, FR11, D3 of add-tracker-port.
     */
    TakeResult claim(TakeOrder order) {
        TaskGit git = wiring.git();
        Path cloneDir = order.run().cloneDir();

        git.worktrees().pruneWorktrees(cloneDir);
        git.branches().harden(cloneDir);

        TaskState notYetStarted = TaskState.atStageStart(
                order.run().definition().stages().getFirst().name());
        var baseRequest =
                new FreshClaimBaseBinding.Request(order.run().base(), order.trackerTask(), wiring.trustedBase());
        return FreshClaimBaseBinding.resolve(
                git.baseRefs(),
                cloneDir,
                baseRequest,
                notYetStarted,
                order.tracker(),
                baseBound -> claimAt(order, baseBound));
    }

    /**
     * The remainder of a fresh claim once its base is resolved and refreshed: bind the task tier
     * at that base, create the branch from the resolved ref, and run the engine once.
     */
    private TakeResult claimAt(TakeOrder order, FreshClaimBaseBinding.Bound baseBound) {
        TaskGit git = wiring.git();
        Path worktreesRoot = wiring.worktreesRoot();
        // FR13, D14 of add-base-ref-resolution: the task runs under the definition read from ITS
        // resolved base's law binding, never under the startup one.
        var law = TaskTierLaw.bind(wiring.assembly(), baseBound.lawBinding(), order);
        if (law instanceof TaskTierLaw.Parked(TakeResult parked)) {
            return parked;
        }
        var bound = (TaskTierLaw.Bound) law;
        // D6 of introduce-take-order: from here on only the order re-bound to the task's law is
        // used — the one this method was handed still carries the startup definition.
        TakeOrder lawBound = order.withDefinition(bound.definition());
        Path cloneDir = lawBound.run().cloneDir();
        String taskId = lawBound.taskId();

        var synthesized = TrackerTaskSynthesizer.synthesize(
                lawBound.trackerTask().snapshot(), lawBound.run().definition());
        var taskRepository = git.store().taskRepository(cloneDir, worktreesRoot);
        // FR15, D12 of add-base-ref-resolution: the branch starts at the very commit the task's law
        // was peeled at — the refreshed base — and the resolved ref travels beside it as the pin.
        GitFreshTaskSupport.createTask(
                taskRepository,
                taskId,
                synthesized.context(),
                bound.lawCommit(),
                baseBound.pin(),
                synthesized.initialState());

        Path worktree = TaskWorktreePath.resolve(worktreesRoot, cloneDir, taskId);
        TaskRecord content =
                git.store().readTaskRecord(worktree).orElseThrow(() -> AbsentEnvelope.task(taskId, worktree));
        String branchName = TaskIdSanitizer.branchName(taskId);
        var bootstrap = new ResumeBootstrap(
                taskId,
                content.context(),
                content.outcome(),
                content.lastEscalation(),
                worktree,
                branchName,
                content.baseCommit(),
                content.trackerWritePending(),
                content.pin());

        var execution = new TakeEngineExecution(
                wiring.assembly(),
                git,
                worktreesRoot,
                wiring.abort(),
                wiring.credentialEnvVarsToScrub(),
                wiring.tenure().lossFlag(),
                bound.lawBinding());
        return execution.run(lawBound, bootstrap, synthesized.context(), synthesized.initialState());
    }
}
