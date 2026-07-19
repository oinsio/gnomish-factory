package com.github.oinsio.gnomish.e2e

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates the {@code .gnomish-fixtures/e2e} resource tree (task 9.1, M1): a
 * self-contained project directory — {@code marker.txt} at its root plus a
 * {@code .gnomish/} pipeline defining one {@code work} stage whose {@code verify}
 * list covers all four check types ({@code files_exist}, {@code command},
 * {@code external}, {@code judge}, one vote). {@code --dir} for the real
 * {@code gnomish run} process points at {@link #projectRoot()}.
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
        Path.of(E2eFixture.getResource('/.gnomish-fixtures/e2e').toURI())
    }

    /** @return the fixture's {@code .gnomish/} subdirectory, for direct {@code PipelineLoader} use */
    static Path gnomishDir() {
        Path dir = projectRoot().resolve('.gnomish')
        assert Files.isDirectory(dir)
        dir
    }
}
