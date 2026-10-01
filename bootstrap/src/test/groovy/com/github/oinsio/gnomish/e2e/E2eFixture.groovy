package com.github.oinsio.gnomish.e2e

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates the {@code .gnomish-fixtures/e2e} resource tree (task 9.1, M1): a
 * self-contained project directory — {@code marker.txt} at its root plus a
 * {@code .gnomish/} pipeline defining one {@code work} stage whose {@code verify}
 * list covers all four check types ({@code files_exist}, {@code command},
 * {@code external}, {@code judge}, one vote). {@code --dir} for the real
 * {@code gnomish run} process points at {@code #projectRoot()}.
 *
 * <p>The spawned factory works only in a registered clone, and only a git working tree registers
 * (FR2, FR3 of add-project-registry), so the tree the specs drive is an {@link E2eGitTree} copy,
 * made once per JVM.
 *
 * <p>M1 of add-manual-run.
 */
final class E2eFixture {

    private E2eFixture() {}

    /**
     * @return the fixture project root ({@code --dir} target), resolved from
     *     the test classpath resource {@code /.gnomish-fixtures/e2e}
     */
    static Path projectRoot() {
        PROJECT_ROOT
    }

    /** @return the {@code e2e-broken} fixture root — a plan stage naming a missing instructions file */
    static Path brokenRoot() {
        BROKEN_ROOT
    }

    private static final Path PROJECT_ROOT = E2eGitTree.copyOf('e2e')
    private static final Path BROKEN_ROOT = E2eGitTree.copyOf('e2e-broken')

    /** @return the fixture's {@code .gnomish/} subdirectory, for direct {@code PipelineLoader} use */
    static Path gnomishDir() {
        Path dir = projectRoot().resolve('.gnomish')
        assert Files.isDirectory(dir)
        dir
    }
}
