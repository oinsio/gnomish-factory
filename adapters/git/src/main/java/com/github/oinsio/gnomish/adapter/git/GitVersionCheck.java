package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.GitVersionRefusedException;
import com.github.oinsio.gnomish.gittransfer.GitVersion;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The git version floor, checked once at startup before any transfer (FR10, design D8 of
 * own-git-transfer-argv): {@code git --version} runs through the one runner, its stdout is parsed
 * into the leaf's typed {@link GitVersion} — the parser warrant this class carries — and a version
 * below {@link GitVersion#FLOOR}, or a git that reports no version at all, ends the process with a
 * {@link GitVersionRefusedException} naming floor, installed and {@link GitVersion#FLOOR_REASON}.
 * One floor covers every flag the transfer owner uses, so there is no per-flag gating (D8).
 *
 * <p>Lives beside the runner because {@code GitProcessRunner.run} and {@code GitCommandResult} are
 * package-private; it is public so the composition root can wire it as a bean and the command
 * runner can call it before dispatch — the one place {@code run}, {@code take} and {@code serve}
 * all pass through.
 *
 * <p>Once per process (NFR-R2): the first {@link #verify()} records the version it accepted, and
 * every later call answers from that record without a subprocess. The record is a volatile field
 * rather than a lock because the check is idempotent — two concurrent first calls would both run
 * {@code git --version} and both record the same value, which is harmless; what the field
 * guarantees is that a caller after the first sees it.
 *
 * <p>The one ERROR of this failure class is logged here (NFR-O1); the command that ends on the
 * exception prints its message and nothing else, so the operator reads a precondition (UX2).
 *
 * <p>Implements FR10, NFR-R2, NFR-O1, UX2 of own-git-transfer-argv.
 */
@UntrustedParser
public final class GitVersionCheck {

    private static final Logger log = LoggerFactory.getLogger(GitVersionCheck.class);

    /** {@code git --version} needs no repository; the runner only asks that the directory exist. */
    private static final Path ANY_DIRECTORY = Path.of(".");

    private final GitProcessRunner runner;

    /** The version the first call accepted; a later call answers from it without a subprocess. */
    private volatile @Nullable GitVersion accepted;

    /**
     * @param runner the one git subprocess runner every git-backed port shares
     */
    public GitVersionCheck(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Reads the installed git's version, once per process, and refuses below the floor. Returns
     * nothing rather than the version: the leaf's type is an implementation edge of this module,
     * and the composition root that calls this needs only the refusal.
     *
     * @throws GitVersionRefusedException below the floor, or when git reports no version
     */
    public void verify() {
        if (accepted != null) {
            return;
        }
        GitCommandResult result = runner.run(ANY_DIRECTORY, "--version");
        Optional<GitVersion> reported =
                result.exitCode() == 0 ? GitVersion.parse(result.stdout().forParsing()) : Optional.empty();
        if (reported.isEmpty()) {
            UntrustedText printed = result.exitCode() == 0 ? result.stdout() : result.stderr();
            throw refuse(
                    "unreported (git --version printed: " + printed.forLog() + ")",
                    new GitVersionRefusedException(GitVersion.FLOOR.toString(), printed, GitVersion.FLOOR_REASON));
        }
        GitVersion installed = reported.get();
        if (installed.isBelow(GitVersion.FLOOR)) {
            throw refuse(
                    installed.toString(),
                    new GitVersionRefusedException(
                            GitVersion.FLOOR.toString(), installed.toString(), GitVersion.FLOOR_REASON));
        }
        log.info("git version {} meets the floor {}", installed, GitVersion.FLOOR);
        accepted = installed;
    }

    /** The one ERROR of this failure class: logged here, printed by the command that ends on it. */
    private static GitVersionRefusedException refuse(String installed, GitVersionRefusedException refusal) {
        log.error(
                OperatorEvent.STARTUP_GIT_VERSION_REFUSED.head()
                        + "git version check refused startup: floor={}, installed={}: {}",
                GitVersion.FLOOR,
                installed,
                GitVersion.FLOOR_REASON);
        return refusal;
    }
}
