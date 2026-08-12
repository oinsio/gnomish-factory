package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.SandboxProperties;
import com.github.oinsio.gnomish.adapter.agent.FreshJudgeEnvironments;
import com.github.oinsio.gnomish.adapter.check.SandboxCheckEnvironmentSource;
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist;
import com.github.oinsio.gnomish.adapter.environment.ContainerEnvironments;
import com.github.oinsio.gnomish.adapter.environment.EnvironmentLease;
import com.github.oinsio.gnomish.adapter.environment.LeasedEnvironment;
import com.github.oinsio.gnomish.adapter.environment.Segment;
import com.github.oinsio.gnomish.adapter.git.AttemptCommitRef;
import com.github.oinsio.gnomish.adapter.git.BranchPush;
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch;
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.EnvironmentSalvage;
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.PushBestEffortAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.RemoteAttemptDelivery;
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource;
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck;
import com.github.oinsio.gnomish.adapter.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** Factory-authored state files are small; a 1&nbsp;MiB read cap is generous (NFR-S3). */
    private static final long FILE_READ_CAP = 1L << 20;

    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final String taskId;
    private final String branch;
    private final ContainerEnvironments environments;
    private final EnvironmentLease lease;
    private final AttemptCommitRef attemptCommit = new AttemptCommitRef();
    private final GitObjects gitObjects;
    private final GitObjectsTaskRepository taskRepository;
    private final FreshJudgeEnvironments judgeEnvironments;
    private final BranchPush push;

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
     * Builds the run's container support. The child-env allowlist mirrors {@link
     * ManualRunAssembly#assemble}'s own composition — operator passthrough plus the declared
     * credential names (the external-check token added when that adapter is configured, FR26) —
     * because the environments compose exec children before the assembly exists.
     *
     * @param cloneDir the factory clone; never null
     * @param taskId the task whose environments this run owns; never blank
     * @param segments the run's segment plan; never empty
     * @param sandboxProperties the operator sandbox config; never null
     * @param factoryProperties factory config, for the external-check credential name; never null
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential names;
     *     empty for plain {@code gnomish run}
     */
    static ContainerRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            List<String> credentialEnvVarsToScrub) {
        var runner = new GitProcessRunner();
        List<String> credentials = new ArrayList<>(credentialEnvVarsToScrub);
        if (factoryProperties.check().github().configured()) {
            credentials.add(GithubCheckClientFactory.TOKEN_ENV_VAR);
        }
        var allowlist = ChildEnvAllowlist.of(sandboxProperties.envPassthrough(), credentials);
        var environments = ContainerEnvironments.forTask(
                TaskIdSanitizer.sanitize(taskId),
                cloneDir,
                new ContainerHarvestFetch(runner, cloneDir),
                sandboxProperties,
                new SystemClock(),
                allowlist,
                new ThreadSleeper(),
                Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir")), "gnomish-guard"));
        return new ContainerRunSupport(runner, cloneDir, taskId, environments, segments);
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

    /**
     * Runs the startup orphan sweep (FR11, NFR-R2): objects a dead instance left labelled but no
     * live task owns are removed, this task's own environments preserved. Delegates to the
     * environments seam; a missing Docker runtime is a logged no-op, never a failure.
     */
    void sweepOrphans() {
        environments.sweepOrphans();
    }

    /**
     * Completed terminal boundary (D19 ordering): dispose the environment first — the last
     * in-box commit was the state commit — then record the outcome and cleanup commits
     * factory-side, then push best-effort.
     */
    void completeAndDispose(TaskState finalState) {
        judgeEnvironments.disposeCurrent();
        lease.dispose();
        taskRepository.recordOutcome(taskId, new TaskOutcome.Completed(finalState));
        push.pushBestEffort(cloneDir, branch);
    }

    /**
     * Aborted terminal boundary (D19): the outcome commits on the last harvested tip and is
     * pushed best-effort; the violating box is kept as evidence — the caller's keep path stops
     * it ({@link #keepStopped}), volume and network retained.
     */
    void recordAborted(TaskOutcome.Aborted outcome) {
        taskRepository.recordOutcome(taskId, outcome);
        push.pushBestEffort(cloneDir, branch);
    }

    /**
     * Keep semantics for a run that ended without disposing (aborted, EOF-interrupted dialog):
     * the container is stopped so no gnome process keeps executing, volume and network remain
     * for salvage and resume; fresh judge boxes are disposed — they hold nothing durable.
     */
    void keepStopped() {
        judgeEnvironments.disposeCurrent();
        environments.stopKeeping();
    }

    /** Reads the last durably committed {@code state.json} from the branch tip as bare objects (FR17). */
    TaskState readFinalState() {
        byte[] bytes = gitObjects.readBlob(tip(), ".gnomish-task/state.json", FILE_READ_CAP);
        return StateJsonMapper.fromDto(StateJsonMapper.readDto(new String(bytes, StandardCharsets.UTF_8)));
    }

    /**
     * The recorded state at the branch tip, or the initial state at {@code firstStage} when no
     * round ever persisted one — a task killed during its very first round has only the creation
     * commit's {@code task.json} on the branch (FR6).
     */
    TaskState readStateOrInitial(String firstStage) {
        try {
            return readFinalState();
        } catch (RuntimeException e) {
            return TaskState.atStageStart(firstStage);
        }
    }

    /** Reads the branch tip's {@code task.json} as bare objects — context, outcome, escalation (FR17). */
    TaskJsonContent readTaskJson() {
        byte[] bytes = gitObjects.readBlob(tip(), ".gnomish-task/task.json", FILE_READ_CAP);
        return TaskJsonMapper.fromDto(TaskJsonMapper.readDto(new String(bytes, StandardCharsets.UTF_8)));
    }

    /** Disposes a kept environment left by a previous instance ({@code --discard-work}, FR6). */
    void disposeExistingEnvironment() {
        environments.disposeExisting();
    }

    private ObjectId tip() {
        return gitObjects
                .resolveRef("refs/heads/" + branch)
                .orElseThrow(() -> new IllegalStateException("task branch \"" + branch + "\" disappeared"));
    }
}
