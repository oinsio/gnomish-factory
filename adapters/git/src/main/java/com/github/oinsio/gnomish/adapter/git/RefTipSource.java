package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.TipRelation;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads a task branch at its registered commit, in a clone — the medium {@code status}, take
 * routing and the denial restore use, where no worktree exists and none should be created. The
 * register is read once, when the source is made ({@link #at}): every fact this source answers
 * comes from that one registered commit, and the located ref contributes its relation to it alone.
 *
 * <p>Implements FR1 of harden-task-branch-contract; FR13, NFR-S2 of tighten-spec-contract.
 */
public final class RefTipSource implements BranchTipSource {

    private final Optional<GitShowTip> registered;
    private final TipRelation relation;

    private RefTipSource(Optional<GitShowTip> registered, TipRelation relation) {
        this.registered = registered;
        this.relation = relation;
    }

    /**
     * The source of one task branch: the task's register read against the located tip.
     *
     * @param runner the git subprocess runner this read shares with the rest of the run
     * @param cloneDir the clone to read in
     * @param taskId the tracker's original taskId, or its branch's core string
     * @param tipRef the located branch ref, e.g. {@code refs/heads/gnomish/PROJ-1} or {@code
     *     refs/remotes/origin/gnomish/PROJ-1}
     * @return the source
     */
    public static RefTipSource at(GitRunner runner, Path cloneDir, String taskId, String tipRef) {
        FactoryRegisterRead.RegisterReading reading = new FactoryRegisterRead(runner).at(cloneDir, taskId, tipRef);
        return new RefTipSource(
                reading.registered().map(commit -> new GitShowTip(runner, cloneDir, commit)), reading.relation());
    }

    @Override
    public Optional<UntrustedText> readAtRegister(String path) {
        return registered.flatMap(commit -> commit.readAtRegister(path));
    }

    @Override
    public boolean registeredCleanup() {
        return registered.map(GitShowTip::isCleanupCommit).orElse(false);
    }

    @Override
    public TipRelation tipRelation() {
        return relation;
    }
}
