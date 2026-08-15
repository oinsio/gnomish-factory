package com.github.oinsio.gnomish.adapter.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Neutralizes git hooks on a factory-managed clone by pointing its {@code core.hooksPath} at an
 * empty, factory-owned directory (FR17, design D11, D20). A host-mode worktree shares the factory
 * clone's {@code .git}, so a gnome- or build-installed hook (a husky-class {@code pre-commit}) that
 * the operator's clone carries could otherwise fire during a factory-side {@code git commit} —
 * running untrusted code in the factory's own filesystem namespace. Setting {@code core.hooksPath}
 * to an empty directory means git finds no hook to run for any operation on the clone or any of its
 * linked worktrees (they inherit the shared {@code .git/config}).
 *
 * <p>Runner-start hygiene, the config-write twin of {@link TaskWorktreeCleanup#pruneWorktrees}:
 * meant to run once when the factory starts operating on a clone. Idempotent — re-running rewrites
 * the same value and re-ensures the same directory. Bare-object commits done through {@code
 * gitobjects} (design D19) are already hooks-safe per invocation and do not depend on this; this
 * closes the checkout/worktree path that {@code git commit} takes in host mode.
 *
 * <p>Reads of gnome branches are already hook-safe by construction: {@link BranchStateReader},
 * {@link DeliveredBranchReader}, {@link TaskBranchLister}, and {@link UsageHistoryWalker} all read
 * branch content as bare git objects ({@code git show <ref>:<path>}, {@code cat-file}), never
 * checking gnome-branch content out into a factory-owned path — the other half of FR17.
 *
 * <p>Implements FR17 of add-sandbox-core.
 */
public final class FactoryCloneHardening {

    /** Name of the empty hooks directory created under the clone's git dir. */
    static final String EMPTY_HOOKS_DIR = "gnomish-empty-hooks";

    private final GitProcessRunner runner;

    /**
     * @param runner the git subprocess runner
     */
    public FactoryCloneHardening(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Points {@code cloneDir}'s {@code core.hooksPath} at an empty directory under its git dir,
     * creating that directory if absent. The path is written absolute so it resolves the same from
     * the clone and from any linked worktree cwd.
     *
     * @param cloneDir the working directory of the factory-managed clone to harden
     * @throws FactoryCloneHardeningException if the git dir cannot be resolved, the empty directory
     *     cannot be created, or the config write fails — hardening is fatal, never best-effort
     */
    public void harden(Path cloneDir) {
        Path gitDir = resolveGitDir(cloneDir);
        Path emptyHooks = gitDir.resolve(EMPTY_HOOKS_DIR);
        try {
            Files.createDirectories(emptyHooks);
        } catch (IOException e) {
            throw new FactoryCloneHardeningException(cloneDir.toString(), e);
        }
        GitCommandResult config = runner.run(
                cloneDir,
                "config",
                "core.hooksPath",
                emptyHooks.toAbsolutePath().toString());
        if (config.exitCode() != 0) {
            throw new FactoryCloneHardeningException(cloneDir.toString(), config.stderr());
        }
    }

    private Path resolveGitDir(Path cloneDir) {
        GitCommandResult gitDir = runner.run(cloneDir, "rev-parse", "--absolute-git-dir");
        if (gitDir.exitCode() != 0) {
            throw new FactoryCloneHardeningException(cloneDir.toString(), gitDir.stderr());
        }
        return Path.of(gitDir.stdout().trim());
    }
}
