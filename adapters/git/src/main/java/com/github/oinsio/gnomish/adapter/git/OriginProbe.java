package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;

/**
 * The cheapest question that can be put to {@code origin}: does it answer at all? One {@code git
 * ls-remote origin HEAD} — no tracker call, no object transfer, no ref written.
 *
 * <p>Its job is classification, not information. When a fetch fails, git's own words are the wrong
 * evidence to decide <em>why</em>: they are localized, they are reworded between releases, and
 * "could not resolve host" and "server refused this request" arrive through the same non-zero exit.
 * Asking the remote a second, simpler question separates them by behavior instead — the same move
 * {@link TaskBranchLocator} makes when a narrow fetch fails, so that only a remote which actually
 * answered may put a task-level fact on the record.
 *
 * <p>Implements FR9, FR14 of add-base-ref-resolution.
 */
// Not a record: this is a behavior-bearing reader over the git seam (a collaborator, not immutable
// data), kept as a plain final class for parity with its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
final class OriginProbe {

    private final GitProcessRunner runner;

    OriginProbe(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Whether {@code origin} answered a refs read from the clone at {@code cloneDir}.
     *
     * @param cloneDir the factory clone the probe runs from; never null
     * @return true only when the read ran to its own exit and origin served it
     */
    boolean answers(Path cloneDir) {
        GitCommandResult probe = LsRemote.refs(runner, cloneDir, "HEAD");
        return probe.termination() == Termination.EXITED && probe.exitCode() == 0;
    }
}
