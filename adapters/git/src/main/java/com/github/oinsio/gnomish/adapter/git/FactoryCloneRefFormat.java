package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * ({@code FactoryRegisterOwnerSpec} pins both). Reached through {@link GitTaskBranches#harden},
 * which every run path calls before it writes the clone's configuration or worktree registry — the
 * same touchpoint as {@link FactoryCloneHardening} — and through {@link TouchpointFetch}, before
 * the first ref every task touchpoint writes, including the take router's classification and the
 * {@code status}/{@code usage} reads that never harden. A worktree shares its clone's ref store, so checking the clone
 * covers every worktree of it. {@code git rev-parse --show-ref-format} exists since git 2.45, below
 * the factory's floor of 2.51 ({@code GitVersionCheck}).
 *
 * <p>The one ERROR of this failure class is logged here; the command that ends on the exception
 * prints its message and nothing else, so the operator reads a precondition.
 *
 * <p>Implements NFR-S2 of tighten-spec-contract (findings F122, F135).
 */
@UntrustedParser
final class FactoryCloneRefFormat {

    private static final Logger log = LoggerFactory.getLogger(FactoryCloneRefFormat.class);

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
        log.error(
                OperatorEvent.FACTORY_CLONE_REF_FORMAT_REFUSED.head()
                        + "factory clone refused: {} is not on the {} ref backend (reported: {})",
                cloneDir,
                REQUIRED,
                reported.forLog());
        throw new CloneRefFormatRefusedException(cloneDir.toString(), REQUIRED, reported);
    }
}
