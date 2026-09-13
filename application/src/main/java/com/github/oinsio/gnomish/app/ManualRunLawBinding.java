package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The law binding of a manual {@code gnomish run} in git mode (design D12 of
 * add-base-ref-resolution), host and container alike: with {@code --base} the law is read from
 * that ref's own commit out of git objects — offline, no fetch — and without it the clone's
 * working tree stays the law, so a pipeline author's uncommitted {@code .gnomish/} edit is still
 * what runs (FR8, UX3).
 *
 * <p>Selection is by fact, not by mode: the same rule that sends {@code take} and {@code serve} to
 * a law commit sends a based manual run there too, and only the un-based manual run — which
 * resolved no ref at all — keeps the working tree.
 *
 * <p><b>The law is bound before the branch is created</b> (FR15, D12 revised 2026-09-10): {@link
 * #bind} peels the binding once and hands back the commit, which is the branch's start point as
 * well as the law's. The manual tier thereby obeys the "a base name never reaches the repository
 * port" rule with no fetch of its own — the peel is a local read.
 *
 * <p>Implements FR8, FR11, FR15 of add-base-ref-resolution.
 */
final class ManualRunLawBinding {

    private ManualRunLawBinding() {}

    /**
     * One manual run's bound law: the binding the run assembles under, and the commit it was peeled
     * to — the same commit the task branch starts from.
     *
     * @param binding the law binding for this run
     * @param lawCommit the commit that binding peeled to
     */
    record Bound(LawBinding binding, ObjectId lawCommit) {}

    /**
     * Binds one manual run's law and peels it, refusing rather than falling back to a name.
     *
     * <p>Implements FR8, FR11, FR15 of add-base-ref-resolution.
     *
     * @param assembly the run assembly that owns the peel; never null
     * @param cloneDir the {@code --dir} project clone
     * @param base the {@code --base} override, or {@code null} for the clone's current state
     * @return the binding and its law commit; never null
     * @throws UsageException when {@code cloneDir} is no git repository, so a working-tree binding
     *     resolves no checkout to start a task branch from
     */
    static Bound bind(RunAssembly assembly, Path cloneDir, @Nullable String base) {
        LawBinding binding = of(cloneDir, base);
        ObjectId lawCommit = assembly.lawCommitOf(binding);
        if (lawCommit == null) {
            throw new UsageException("cannot start a git-mode task in " + cloneDir + ": it is not a git repository"
                    + " with a checked-out commit, so there is no commit to start the task branch from. Run"
                    + " 'gnomish run' against a clone, or use the in-place mode, which creates no branch.");
        }
        return new Bound(binding, lawCommit);
    }

    /**
     * The binding for one manual run — the medium selection alone; {@link #bind} is the entry point,
     * so no caller holds an unpeeled manual binding.
     *
     * @param cloneDir the {@code --dir} project clone
     * @param base the {@code --base} override, or {@code null} for the clone's current state
     * @return the git-objects binding at {@code base}, or the clone's working tree; never null
     */
    private static LawBinding of(Path cloneDir, @Nullable String base) {
        return base == null ? LawBinding.workingTree(cloneDir) : LawBinding.atRevision(cloneDir, base);
    }
}
