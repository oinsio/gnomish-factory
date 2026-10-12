package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.workspace.WorkspaceFactory;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

/**
 * The host realization of {@link WorkspaceFactory}: a {@link DirectoryWorkspace} over the directory
 * a run works in. The composition root builds it once ({@code ManualRunConfiguration#workspaceFactory})
 * and hands it to {@link PipelineStartup} and to {@link ManualRunAssembly}, the two owners every
 * host workspace is opened through, so no use case in {@code :application} constructs the adapter
 * itself (finding F119, design D12 of tighten-spec-contract).
 *
 * <p>{@link DirectoryWorkspace} itself stays in {@code :application}: the check runners and the
 * agent and git adapters read its root by type, and those modules cannot reach {@code :bootstrap}.
 * Only its construction lives here.
 *
 * <p>Implements FR13 of tighten-spec-contract.
 */
// Null-marked explicitly (JSpecify): this module carries no package-info.
@NullMarked
public final class DirectoryWorkspaceFactory implements WorkspaceFactory {

    @Override
    public Workspace at(Path root) {
        return new DirectoryWorkspace(root);
    }
}
