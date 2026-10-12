package com.github.oinsio.gnomish.app.port.workspace;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import java.nio.file.Path;

/**
 * Opens the {@link Workspace} a host run works in at a directory on disk — the operator's {@code
 * --dir} for a manual run, the task worktree for a git-mode run and for {@code take}. A use case
 * names this port and never the concrete workspace it yields: the realization is supplied by the
 * composition root ({@code :bootstrap}), so the use-case layer constructs no adapter of {@link
 * Workspace} itself (finding F119, design D12 of tighten-spec-contract). The container medium has
 * its own workspace, built by the root with the rest of a run's sandbox support, and does not pass
 * through here.
 *
 * <p>Implements FR12 of split-into-modules; FR13 of tighten-spec-contract.
 */
@FunctionalInterface
public interface WorkspaceFactory {

    /**
     * Opens the workspace rooted at {@code root}.
     *
     * @param root the directory the run works in; must already exist
     * @return the workspace over {@code root}; never null
     * @throws IllegalArgumentException if {@code root} is not an existing directory
     */
    Workspace at(Path root);
}
