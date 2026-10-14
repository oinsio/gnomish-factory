package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;

/**
 * The precondition the git adapter refuses a factory clone on: the clone does not report the ref
 * backend the factory's ref transactions need. The factory moves a task branch and its register
 * in one {@code git update-ref --stdin} transaction, and only the reftable backend commits that
 * transaction as one write on disk; on the files backend a kill of git between two lock-file
 * renames could split them (design D13 of tighten-spec-contract, as amended 2026-10-13). The
 * message names the clone, what git reported and the remedy — a fresh clone on the required
 * backend, since the project is pre-release and migrates nothing itself, or git's own in-place
 * {@code git refs migrate}, which refuses a clone that has worktrees — so the operator reads a
 * precondition rather than a crash; the command that ends on it prints the message and nothing
 * else.
 *
 * <p>Declared beside {@link GitVersionRefusedException} so the command layer's exception reporting
 * can name it without reaching into the adapter; the adapter throws it, the reporter prints it.
 * What git printed travels as a carrier and renders through the log exit, like every exception
 * that quotes a machine's words.
 *
 * <p>Implements NFR-S2 of tighten-spec-contract (findings F122, F135).
 */
public final class CloneRefFormatRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param cloneDir the factory clone refused
     * @param required the ref backend the factory requires, as {@code --ref-format} spells it
     * @param reported what {@code git rev-parse --show-ref-format} printed for the clone, as captured
     */
    public CloneRefFormatRefusedException(String cloneDir, String required, UntrustedText reported) {
        super("the factory clone \"" + cloneDir + "\" is not on the " + required + " ref backend (git rev-parse"
                + " --show-ref-format printed: " + reported.forLog() + "); the factory moves a task branch and"
                + " its register in one ref transaction, which only " + required + " writes atomically. Re-clone"
                + " the project from origin with git's clone option `--ref-format=" + required + "`"
                + " and register the new clone with `gnomish project add`; a fresh clone holds only what origin"
                + " has, so push any branch that exists only in this clone first. Alternatively, convert a clone"
                + " with no worktrees in place with `git refs migrate --ref-format=" + required + "`.");
    }
}
