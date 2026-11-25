package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.CommitMetadata;
import com.github.oinsio.gnomish.gitobjects.CommitRequest;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.gitobjects.TreeEdit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The host end of the pending commit (design D21 of migrate-verify-to-executor-contract, "Host:
 * built from objects, no worktree, no index"): the commit is written as objects in the host
 * clone's {@code .git} through {@link GitObjects}, the way {@link GitObjectsTerminalCommits#cleanUp}
 * writes the host cleanup — {@code state.json} becomes a blob, the tree is the round's last
 * registered commit's tree with that one blob replaced, the commit's only parent is that registered
 * commit, and the branch moves from it to the commit together with the register in one ref
 * transaction, under a compare-and-swap on the registered commit.
 *
 * <p>Why from objects: a pending check's run is still alive in the worktree when the write happens.
 * Nothing it staged or committed can enter the commit — the tree and the parent are the registered
 * commit's, never the index's or the branch tip's — and no step before the decision takes a lock
 * without a compare-and-swap behind it ({@code lock-scope.md}, "Writers from different
 * processes"): the private index is the library's own, and the ref transaction is the first lock two
 * writers share. A run that committed moved the branch, so the compare-and-swap refuses and nothing
 * lands. The worktree is left lagging the commit; the persistence settles that lag after a pending
 * round commit only, when nothing of the round runs there ({@link PendingStateLag#afterLanding}).
 *
 * <p>The clone's {@code .git} is the worktree's common directory, asked of git once per write.
 *
 * <p>Implements FR2, FR20, NFR-R1 of migrate-verify-to-executor-contract.
 */
@UntrustedParser
final class HostPendingCommit {

    private final GitRunner runner;
    private final Path worktree;
    private final ClaimEpochSource epochs;
    private final InstantSource clock;

    /**
     * @param runner the git subprocess seam the common directory is asked through
     * @param worktree the task worktree; the clone's {@code .git} is its common directory
     * @param epochs the tenure the commit is stamped with
     * @param clock the time the commit carries
     */
    HostPendingCommit(GitRunner runner, Path worktree, ClaimEpochSource epochs, InstantSource clock) {
        this.runner = runner;
        this.worktree = worktree;
        this.epochs = epochs;
        this.clock = clock;
    }

    /**
     * Lands the pending commit on {@code registered}.
     *
     * @param write the call: the subject, the round's key, the task
     * @param state the state the commit's {@code state.json} carries
     * @param registered the round's last registered commit: the parent and the compare-and-swap's
     *     expectation
     * @return the pending commit that landed
     * @throws GitPersistFailedException if anything failed; nothing moved then
     */
    String land(PendingWrite write, TaskState state, String registered) {
        String taskId = write.taskId();
        try {
            byte[] json = TaskStateJson.mapper().writeValueAsBytes(StateJsonMapper.toDto(state));
            ObjectId parent = ObjectId.of(registered);
            String message = ClaimEpochTrailer.stamp(
                    write.subject(), epochs.epochFor(taskId).orElse(null));
            var metadata = new CommitMetadata(
                    TaskLifecycleCommitWriter.FACTORY_IDENTITY,
                    clock.instant(),
                    TaskLifecycleCommitWriter.FACTORY_IDENTITY,
                    clock.instant(),
                    message);
            var request = new CommitRequest(
                            TaskBranchRefs.head(taskId),
                            Optional.of(parent),
                            parent,
                            List.of(new TreeEdit.PutFile(EnvelopePaths.STATE_JSON_PATH, json)),
                            metadata)
                    .alongside(FactoryRegisterRefs.of(taskId).name());
            return objects(write).commit(request).hex();
        } catch (GitPersistFailedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new GitPersistFailedException(
                    taskId, write.key().stage(), write.key().attempt(), "pending commit", e);
        }
    }

    private GitObjects objects(PendingWrite write) {
        GitCommandResult common = runner.run(worktree, "rev-parse", "--path-format=absolute", "--git-common-dir");
        if (!common.succeeded()) {
            throw new GitPersistFailedException(
                    write.taskId(),
                    write.key().stage(),
                    write.key().attempt(),
                    "pending commit",
                    common.failureDetail("clone git directory read"));
        }
        // @UntrustedParser warrant (design D11 of type-untrusted-text): the answer becomes a Path,
        //     refused unless absolute, that is handed back to git as its git directory and never
        //     shown to a reader.
        Path gitDir = Path.of(common.stdout().forParsing().strip());
        if (!gitDir.isAbsolute()) {
            throw new GitPersistFailedException(
                    write.taskId(),
                    write.key().stage(),
                    write.key().attempt(),
                    "pending commit",
                    UntrustedText.factory(
                            "git rev-parse --git-common-dir in " + worktree + " printed no absolute path"));
        }
        return GitObjects.open(gitDir, Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir"))));
    }
}
