package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.SandboxProperties;
import com.github.oinsio.gnomish.adapter.agent.FreshJudgeEnvironments;
import com.github.oinsio.gnomish.adapter.check.SandboxCheckEnvironmentSource;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.environment.ContainerEnvironments;
import com.github.oinsio.gnomish.adapter.environment.EnvironmentLease;
import com.github.oinsio.gnomish.adapter.environment.LeasedEnvironment;
import com.github.oinsio.gnomish.adapter.environment.Segment;
import com.github.oinsio.gnomish.adapter.git.AttemptCommitRef;
import com.github.oinsio.gnomish.adapter.git.BranchPush;
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.EnvironmentSalvage;
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.PushBestEffortAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.RemoteAttemptDelivery;
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource;
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck;
import com.github.oinsio.gnomish.adapter.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The per-run bundle of container-mode collaborators (the integration pass of
 * add-sandbox-core): one place that assembles the environment lease over the
 * segment plan, the attempt-commit ref, the sandboxed persistence with
 * best-effort push, the factory-side lifecycle repository over bare git
 * objects (D19), salvage, and the {@link SandboxRunPieces} handed to {@link
 * ManualRunAssembly#withSandbox}. Both the fresh container run and the
 * container resume build exactly one of these once the task branch exists.
 *
 * <p>Implements FR3, FR5, FR6, FR12, FR21, FR25 of add-sandbox-core.
 */
final class ContainerRunSupport {

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

    /** Builds the run's container support. Delegated to {@link ContainerRunSupportFactory} for file size. */
    static ContainerRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            List<String> credentialEnvVarsToScrub) {
        return ContainerRunSupportFactory.create(
                cloneDir, taskId, segments, sandboxProperties, factoryProperties, credentialEnvVarsToScrub);
    }

    /** The strict sandboxed persistence with the best-effort post-round push (FR5, FR21, FR22). */
    AttemptPersistence persistence() {
        var strict = new EnvironmentAttemptPersistence(
                new LeasedEnvironment(lease::current), runner, cloneDir, gitObjects, taskId, attemptCommit);
        return new PushBestEffortAttemptPersistence(strict, push, cloneDir, branch);
    }

    /** The adapter bundle {@link ManualRunAssembly#withSandbox} swaps in. */
    SandboxRunPieces pieces(SnapshotTipCheck.@Nullable InterruptedVerification pendingVerification) {
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
    AttemptCommitWorkspace workspace() {
        return new AttemptCommitWorkspace(attemptCommit);
    }

    /** The factory-side lifecycle repository over bare git objects (FR25, D19). */
    GitObjectsTaskRepository taskRepository() {
        return taskRepository;
    }

    /** The run's environment lease, for resume-time salvage and reattach. */
    EnvironmentLease lease() {
        return lease;
    }

    /** The sandboxed salvage over the leased environment (FR6). */
    EnvironmentSalvage salvage() {
        return new EnvironmentSalvage(new LeasedEnvironment(lease::current));
    }

    /** The snapshot-without-state classifier of resume (FR21, D15). */
    SnapshotTipCheck snapshotTipCheck() {
        return new SnapshotTipCheck(runner, cloneDir);
    }

    /** The task branch name, {@code gnomish/<sanitized taskId>}. */
    String branch() {
        return branch;
    }

    /** Runs the startup orphan sweep (FR11, NFR-R2). Delegated to {@link ContainerRunTermination}. */
    void sweepOrphans() {
        ContainerRunTermination.sweepOrphans(this);
    }

    /** Completed terminal boundary (D19). Delegated to {@link ContainerRunTermination}. */
    void completeAndDispose(TaskState finalState) {
        ContainerRunTermination.completeAndDispose(this, finalState);
    }

    /** Aborted terminal boundary (D19). Delegated to {@link ContainerRunTermination}. */
    void recordAborted(TaskOutcome.Aborted outcome) {
        ContainerRunTermination.recordAborted(this, outcome);
    }

    /** Keep semantics for a run that ended without disposing. Delegated to {@link ContainerRunTermination}. */
    void keepStopped() {
        ContainerRunTermination.keepStopped(this);
    }

    /** Reads the last durably committed {@code state.json} (FR17). Delegated to {@link ContainerRunTermination}. */
    TaskState readFinalState() {
        return ContainerRunTermination.readFinalState(this);
    }

    /** The recorded state at the branch tip, or the initial state (FR6). Delegated to {@link ContainerRunTermination}. */
    TaskState readStateOrInitial(String firstStage) {
        return ContainerRunTermination.readStateOrInitial(this, firstStage);
    }

    /** Reads the branch tip's {@code task.json} (FR17). Delegated to {@link ContainerRunTermination}. */
    TaskJsonContent readTaskJson() {
        return ContainerRunTermination.readTaskJson(this);
    }

    /** Disposes a kept environment left by a previous instance ({@code --discard-work}, FR6). */
    void disposeExistingEnvironment() {
        ContainerRunTermination.disposeExistingEnvironment(this);
    }
}
