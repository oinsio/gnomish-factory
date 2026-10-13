package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.branch.TipRelation;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The read half of the factory register (design D13 of tighten-spec-contract), and the one
 * producer of {@link RegisteredCommit}: which commit the clone's {@code refs/gnomish-factory/<task>}
 * names, and where a branch tip stands relative to it. The register's name comes from {@link
 * FactoryRegister#of}, the write half's one spelling, so the reader and the writer cannot disagree
 * on where it lives; the touchpoint fetch ({@link TouchpointFetch}) brought origin's into the clone
 * before any of these reads.
 *
 * <p>Every read passes {@link GitReadGate#answered} or {@link VerifiedTip}: an invocation cut off
 * before its own exit is unavailability, never "no register" — reading it as absence would turn a
 * live task {@code Unknown} on a shutdown.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 */
// Not a record: a behavior-bearing reader over the git seam, like its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
public final class FactoryRegisterRead {

    private final GitRunner runner;

    /** @param runner the git subprocess seam; never null */
    public FactoryRegisterRead(GitRunner runner) {
        this.runner = runner;
    }

    /**
     * The commit the clone's register of {@code taskId} names.
     *
     * @param repo the clone, or a worktree of it; the ref store is shared
     * @param taskId the tracker's original taskId, or the core string of its branch name — the two
     *     sanitize to the same register
     * @return the registered commit, or empty when the clone holds no register for the task
     * @throws com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException if the read did
     *     not run to its own exit
     */
    public Optional<RegisteredCommit> registered(Path repo, String taskId) {
        String name = FactoryRegister.of(taskId).name();
        return VerifiedTip.read(GitReadGate.answered(
                        name, "rev-parse", runner.run(repo, "rev-parse", "--verify", "--quiet", name + "^{commit}")))
                .map(RegisteredCommit::new);
    }

    /**
     * The register of {@code taskId} read against the branch tip at {@code tipRef} — the one fact
     * the tip contributes to classification ({@link TipRelation}).
     *
     * @param repo the clone the tip and the register live in
     * @param taskId the tracker's original taskId, or its branch's core string
     * @param tipRef the located branch ref, e.g. {@code refs/heads/gnomish/PROJ-1}
     * @return the registered commit, when there is one, and the tip's relation to it
     */
    RegisterReading at(Path repo, String taskId, String tipRef) {
        String tip = VerifiedTip.required(
                tipRef, "rev-parse", runner.run(repo, "rev-parse", "--verify", "--quiet", tipRef + "^{commit}"));
        Optional<RegisteredCommit> registered = registered(repo, taskId);
        if (registered.isEmpty()) {
            String name = FactoryRegister.of(taskId).name();
            return new RegisterReading(registered, new TipRelation.NoRegister(name, tip, carriesEnvelope(repo, tip)));
        }
        String id = registered.get().id();
        if (id.equals(tip)) {
            return new RegisterReading(registered, new TipRelation.AtRegister());
        }
        GitCommandResult ancestry =
                GitReadGate.answered(id, "merge-base", runner.run(repo, "merge-base", "--is-ancestor", id, tip));
        // Exit 0 is git's "ancestor"; 1 is "not one", and anything else — an object this clone
        // cannot walk — is no better: either way the tip is not above the register, so the branch
        // is quarantined rather than read.
        TipRelation relation =
                ancestry.exitCode() == 0 ? new TipRelation.AboveRegister() : new TipRelation.Diverged(id, tip);
        return new RegisterReading(registered, relation);
    }

    /**
     * The commit a delivered register's cleanup commit was written on: its first parent, the {@code
     * Completed} commit. The completion sequence registers that commit before it writes the cleanup
     * ({@code LifecyclePush} after the outcome, again after the cleanup), so it is a commit the
     * register once named — never one the gnome chose.
     *
     * @param repo the clone
     * @param cleanup the registered cleanup commit
     * @return the {@code Completed} commit, or empty when the cleanup commit has no parent
     */
    Optional<RegisteredCommit> completedBefore(Path repo, RegisteredCommit cleanup) {
        String parent = cleanup.id() + "~1";
        return VerifiedTip.read(GitReadGate.answered(
                        parent,
                        "rev-parse",
                        runner.run(repo, "rev-parse", "--verify", "--quiet", parent + "^{commit}")))
                .map(RegisteredCommit::new);
    }

    /** Whether the tip's tree carries the envelope directory, for presence only. */
    private boolean carriesEnvelope(Path repo, String tip) {
        String entry = tip + ":" + EnvelopePaths.DIR_NAME;
        return GitReadGate.answered(tip, "cat-file", runner.run(repo, "cat-file", "-e", entry))
                        .exitCode()
                == 0;
    }

    /**
     * One reading of the register against a tip.
     *
     * @param registered the registered commit, or empty when the clone holds no register
     * @param relation the tip's relation to it
     */
    record RegisterReading(Optional<RegisteredCommit> registered, TipRelation relation) {}
}
