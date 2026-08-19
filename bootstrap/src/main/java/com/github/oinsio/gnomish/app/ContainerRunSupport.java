package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.FreshJudgeEnvironments;
import com.github.oinsio.gnomish.adapter.check.SandboxCheckEnvironmentSource;
import com.github.oinsio.gnomish.adapter.git.BranchPush;
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.EnvironmentSalvage;
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.PushBestEffortAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.RemoteAttemptDelivery;
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource;
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironments;
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease;
import com.github.oinsio.gnomish.sandbox.environment.LeasedEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The per-run bundle of container-mode collaborators (the integration pass of
 * add-sandbox-core): one place that assembles the environment lease over the
 * segment plan, the attempt-commit ref, the sandboxed persistence with
 * best-effort push, the factory-side lifecycle repository over bare git
 * objects (D19), salvage, and the {@link SandboxRunPieces} handed to {@link
 * RunAssembly#withSandbox}. Both the fresh container run and the
 * container resume build exactly one of these once the task branch exists.
 *
 * <p>The single realization of {@link SandboxRunSupport}: every adapter this class names —
 * Docker, the git subprocess, the bare-object reader — stops here, and the runners above it see
 * the port alone (task 4.4, D12 of split-into-modules).
 *
 * <p>Implements FR3, FR5, FR6, FR12, FR21, FR25 of add-sandbox-core.
 */
final class ContainerRunSupport implements SandboxRunSupport {

    final GitProcessRunner runner;
    final Path cloneDir;
    final String taskId;
    final String branch;
    final ContainerEnvironments environments;
    final EnvironmentLease lease;
    final AttemptCommitRef attemptCommit = new AttemptCommitRef();
    final GitObjects gitObjects;
    final GitObjectsTaskRepository taskRepository;
    final FreshJudgeEnvironments judgeEnvironments;
    final BranchPush push;

    /**
     * Canonical wiring over an already-built environments seam. Package-private (not {@code
     * private}) so daemon-free specs can inject a {@link ContainerEnvironments} over a scripted
     * fake docker CLI — the same seam discipline as {@code ContainerEnvironments}'s own
     * package-private constructor; production always goes through {@link #create}.
     */
    ContainerRunSupport(
            GitProcessRunner runner,
            Path cloneDir,
            String taskId,
            ContainerEnvironments environments,
            List<Segment> segments) {
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.taskId = taskId;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.environments = environments;
        this.lease = new EnvironmentLease(environments::roundEnvironment, branch, segments);
        this.gitObjects = GitObjects.open(
                cloneDir.resolve(".git"), Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir"))));
        this.taskRepository = new GitObjectsTaskRepository(gitObjects);
        this.judgeEnvironments = new FreshJudgeEnvironments(environments::judgeEnvironment, branch);
        this.push = new BranchPush(runner);
    }

    /**
     * Builds the run's container support. Delegated to {@link ContainerRunSupportFactory} for file
     * size. {@code checkCredentialEnvVars} is the union the configured check providers declared
     * through the SPI (FR17, design D11 of add-plugin-architecture) — the composition root resolves
     * it once and hands it down, so nothing here names a vendor credential constant.
     */
    static ContainerRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            SandboxProperties sandboxProperties,
            List<String> checkCredentialEnvVars,
            List<String> credentialEnvVarsToScrub) {
        return ContainerRunSupportFactory.create(
                cloneDir, taskId, segments, sandboxProperties, checkCredentialEnvVars, credentialEnvVarsToScrub);
    }

    /** The strict sandboxed persistence with the best-effort post-round push (FR5, FR21, FR22). */
    @Override
    public AttemptPersistence persistence() {
        var strict = new EnvironmentAttemptPersistence(
                new LeasedEnvironment(lease::current), runner, cloneDir, gitObjects, taskId, attemptCommit);
        return new PushBestEffortAttemptPersistence(strict, push, cloneDir, branch);
    }

    /** The adapter bundle {@link RunAssembly#withSandbox} swaps in. */
    @Override
    public SandboxRunPieces pieces(@Nullable PendingVerification pendingVerification) {
        return new SandboxRunPieces(
                new SandboxRoundEnvironmentSource(lease, runner, cloneDir, taskId, attemptCommit, new SystemClock()),
                judgeEnvironments,
                new SandboxCheckEnvironmentSource(lease, environments, branch),
                gitObjects,
                new RemoteAttemptDelivery(runner, cloneDir, branch),
                attemptCommit,
                pendingVerification);
    }

    /** The engine workspace of a sandboxed run: the attempt-commit ref, never a host path (D15). */
    @Override
    public RecordedAttemptCommitWorkspace workspace() {
        return new RecordedAttemptCommitWorkspace(attemptCommit);
    }

    /** The factory-side lifecycle repository over bare git objects (FR25, D19). */
    @Override
    public GitObjectsTaskRepository taskRepository() {
        return taskRepository;
    }

    /**
     * The run's environment lease, for resume-time salvage and reattach. Not on {@link
     * SandboxRunSupport}: the port exposes the two operations the use cases perform ({@link
     * #reattachFor}, {@link #salvageLeftovers}), not the docker-side lease they perform them
     * through.
     */
    EnvironmentLease lease() {
        return lease;
    }

    /** The sandboxed salvage over the leased environment (FR6); see {@link #lease()} on visibility. */
    EnvironmentSalvage salvage() {
        return new EnvironmentSalvage(new LeasedEnvironment(lease::current));
    }

    /** The snapshot-without-state classifier of resume (FR21, D15); see {@link #lease()} on visibility. */
    SnapshotTipCheck snapshotTipCheck() {
        return new SnapshotTipCheck(runner, cloneDir);
    }

    /** The task branch name, {@code gnomish/<sanitized taskId>}; see {@link #lease()} on visibility. */
    String branch() {
        return branch;
    }

    /**
     * Makes the task's box live again for {@code stage} (FR6) — start a stopped one, recreate over
     * a surviving volume, or seed a fresh clone — through the run's environment lease.
     */
    @Override
    public void reattachFor(String stage) {
        lease.environmentFor(stage);
    }

    /** The sandboxed salvage over the leased environment (FR6). */
    @Override
    public void salvageLeftovers(String taskId) {
        salvage().salvage(taskId);
    }

    /** The snapshot-without-state classifier of resume (FR21, D15). */
    @Override
    public Optional<PendingVerification> pendingVerification() {
        return snapshotTipCheck().inspect(branch);
    }

    /** Runs the startup orphan sweep (FR11, NFR-R2). Delegated to {@link ContainerRunTermination}. */
    @Override
    public void sweepOrphans() {
        ContainerRunTermination.sweepOrphans(this);
    }

    /** Completed terminal boundary (D19). Delegated to {@link ContainerRunTermination}. */
    @Override
    public void completeAndDispose(TaskState finalState) {
        ContainerRunTermination.completeAndDispose(this, finalState);
    }

    /** Aborted terminal boundary (D19). Delegated to {@link ContainerRunTermination}. */
    @Override
    public void recordAborted(TaskOutcome.Aborted outcome) {
        ContainerRunTermination.recordAborted(this, outcome);
    }

    /** Keep semantics for a run that ended without disposing. Delegated to {@link ContainerRunTermination}. */
    @Override
    public void keepStopped() {
        ContainerRunTermination.keepStopped(this);
    }

    /** Reads the last durably committed {@code state.json} (FR17). Delegated to {@link ContainerRunTermination}. */
    @Override
    public TaskState readFinalState() {
        return ContainerRunTermination.readFinalState(this);
    }

    /** The recorded state at the branch tip, or the initial state (FR6). Delegated to {@link ContainerRunTermination}. */
    @Override
    public TaskState readStateOrInitial(String firstStage) {
        return ContainerRunTermination.readStateOrInitial(this, firstStage);
    }

    /** Reads the branch tip's {@code task.json} (FR17). Delegated to {@link ContainerRunTermination}. */
    @Override
    public TaskRecord readTaskJson() {
        return ContainerRunTermination.readTaskJson(this);
    }

    /** Restores the branch tip's denial cursor (FR5). Delegated to {@link ContainerRunTermination}. */
    @Override
    public void restoreDenialCursor() {
        ContainerRunTermination.restoreDenialCursor(this);
    }

    /** Disposes a kept environment left by a previous instance ({@code --discard-work}, FR6). */
    @Override
    public void disposeExistingEnvironment() {
        ContainerRunTermination.disposeExistingEnvironment(this);
    }
}
