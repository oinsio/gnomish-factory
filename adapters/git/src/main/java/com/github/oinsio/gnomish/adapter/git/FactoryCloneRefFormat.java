package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;

/**
 * The factory clone's ref backend, checked where a run first touches the clone (design D13 of
 * tighten-spec-contract, as amended 2026-10-13). {@link FactoryRegister} moves a task branch and
 * its register in one {@code git update-ref --stdin} transaction, and the bare-object writer runs
 * its own; only the reftable backend commits such a transaction as one table write. On the files
 * backend git renames one lock file per ref, so a kill of git between two renames could leave the
 * branch and the register split — the window the register exists to close. The factory does not
 * create the clone (the operator does, and registers it), so it cannot choose the backend; it
 * refuses any other, failing closed before the run writes anything, and names the remedy: a fresh
 * clone on reftable (the project is pre-release; nothing is migrated).
 *
 * <p>The one owner of the requirement: {@link #REQUIRED} is the only production spelling of the
 * backend, and this class the only reader of {@code --show-ref-format}
 * ({@code FactoryRegisterOwnerSpec} pins both). Reached through {@link
 * GitTaskBranches#requireRefFormat}, which every run path calls before its first write to the clone
 * (a branch, a ref, the worktree registry), and through {@link TouchpointFetch}, before the first
 * ref every task touchpoint writes, including the take router's classification and the {@code
 * status}/{@code usage} reads that no run path precedes. A worktree shares its clone's ref store, so checking the clone
 * covers every worktree of it. {@code git rev-parse --show-ref-format} exists since git 2.45, below
 * the factory's git floor ({@code GitVersion.FLOOR}, checked by {@code GitVersionCheck}).
 *
 * <p>The check logs nothing: the refusal carries the message, and the layer that decides on it —
 * the startup assessment, the command, or the daemon condition — writes the one line (design D19
 * of add-sandbox-base-image).
 *
 * <p>Implements NFR-S2 of tighten-spec-contract (findings F122, F135); NFR-O2 of
 * add-sandbox-base-image.
 */
@UntrustedParser
final class FactoryCloneRefFormat {

    /** The ref backend a factory clone must be on, as {@code --ref-format} spells it. */
    static final String REQUIRED = "reftable";

    private final GitRunner runner;

    /**
     * @param runner the git subprocess runner
     */
    FactoryCloneRefFormat(GitRunner runner) {
        this.runner = runner;
    }

    /**
     * Refuses {@code cloneDir} unless git reports it on {@link #REQUIRED}.
     *
     * @param cloneDir the working directory of the factory clone a run starts on
     * @throws CloneRefFormatRefusedException when git reports another backend, or none (a failed
     *     {@code rev-parse}: its stderr is what the refusal quotes)
     */
    void require(Path cloneDir) {
        GitCommandResult result = runner.run(cloneDir, "rev-parse", "--show-ref-format");
        if (result.exitCode() == 0
                && REQUIRED.equals(result.stdout().forParsing().trim())) {
            return;
        }
        UntrustedText reported = result.exitCode() == 0 ? result.stdout() : result.stderr();
        throw new CloneRefFormatRefusedException(cloneDir.toString(), REQUIRED, reported);
    }
}
