package com.github.oinsio.gnomish.adapter.workspace;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The workspace for a manual run: a pre-existing directory on disk, supplied
 * by the operator via {@code --dir} (defaulting to cwd) and never created
 * or written to by the runner itself (design D3, NFR-S1). The engine only
 * ever sees this through the opaque {@link Workspace} marker; check runners
 * and console adapters downcast to {@link #root()} when they need the
 * filesystem path.
 *
 * <p>Implements FR1, FR6, FR7 of add-manual-run.
 */
public final class DirectoryWorkspace implements Workspace {

    private final Path root;

    /**
     * Wraps {@code root} as the workspace, failing fast if it is not a
     * pre-existing directory. No directory is ever created here — the
     * workspace belongs to the operator (design D3).
     *
     * @param root the workspace root, typically resolved from {@code --dir}
     * @throws IllegalArgumentException if {@code root} does not exist or is
     *     not a directory
     */
    public DirectoryWorkspace(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Workspace root is not an existing directory: " + root);
        }
        this.root = root;
    }

    /**
     * The workspace root path, for check runners and console adapters that
     * need the filesystem location (e.g. resolving {@code files_exist}
     * targets or a {@code command} check's working directory).
     *
     * @return the workspace root directory
     */
    public Path root() {
        return root;
    }
}
