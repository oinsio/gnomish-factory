package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Reads the task branch's state straight out of the factory clone's git objects — no checkout, no
 * worktree, no hooks. The container-mode medium: lifecycle commits there are built from bare
 * objects, and the factory never has the branch checked out anywhere to read it from (design D3,
 * design D19 of add-sandbox-core).
 *
 * <p>Implements FR1, FR5 of harden-task-branch-contract.
 */
public final class BareObjectsTipSource implements BranchTipSource {

    /**
     * The same one-megabyte ceiling the other bare-object readers of {@code .gnomish-task/} files
     * use ({@link TaskLifecycleCommitWriter}): a state file this large is a corruption, not a
     * document to load.
     */
    private static final long FILE_READ_CAP = 1L << 20;

    private final GitObjects gitObjects;
    private final ObjectId commit;

    /**
     * @param gitObjects the bare-object library open on the factory clone
     * @param commit the branch tip to read at
     */
    public BareObjectsTipSource(GitObjects gitObjects, ObjectId commit) {
        this.gitObjects = gitObjects;
        this.commit = commit;
    }

    @Override
    public Optional<String> readAtTip(String path) {
        if (!gitObjects.exists(commit, path)) {
            return Optional.empty();
        }
        return Optional.of(new String(gitObjects.readBlob(commit, path, FILE_READ_CAP), StandardCharsets.UTF_8));
    }

    @Override
    public Optional<ClaimEpoch> tipEpoch() {
        return gitObjects.commitMessage(commit).flatMap(ClaimEpochTrailer::parse);
    }

    @Override
    public boolean cleanupCommitInHistory() {
        return gitObjects.historyContains(commit, ServiceCommitMessages.cleanup());
    }
}
