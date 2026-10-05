package com.github.oinsio.gnomish.e2e

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates the {@code .gnomish-fixtures/e2e} resource tree (task 9.1, M1): a
 * self-contained project directory — {@code marker.txt} at its root plus a
 * {@code .gnomish/} pipeline defining one {@code work} stage whose {@code verify}
 * list covers three check kinds ({@code files_exist}, {@code command}, {@code judge},
 * one vote). {@code --dir} for the real {@code gnomish run} process points at
 * {@code #projectRoot()}.
 *
 * <p>The fourth kind, {@code external}, is not in the fixture: no check provider can be
 * configured for a packaged-jar run without a live service the jar cannot see, and a check on
 * an unconfigured provider is a startup refusal. The provider dispatch keeps its own contract
 * specs; the refusal is driven over {@code #unconfiguredProviderRoot()} (design D5 of
 * remove-interactive-console).
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

    /**
     * @return the {@code e2e-unconfigured-provider} fixture root — one stage whose only check is
     *     an {@code external} check on provider {@code github}, run with no
     *     {@code factory.check.github} section (FR3 of remove-interactive-console)
     */
    static Path unconfiguredProviderRoot() {
        UNCONFIGURED_PROVIDER_ROOT
    }

    /**
     * @return the {@code e2e-auto} fixture root — one stage whose only check is a {@code files_exist}
     *     the tree already satisfies, with {@code advancement: auto}, so a clean round finishes the
     *     pipeline without reaching any operator prompt (FR5 of remove-interactive-console)
     */
    static Path autoAdvanceRoot() {
        AUTO_ADVANCE_ROOT
    }

    private static final Path PROJECT_ROOT = E2eGitTree.copyOf('e2e')
    private static final Path BROKEN_ROOT = E2eGitTree.copyOf('e2e-broken')
    private static final Path UNCONFIGURED_PROVIDER_ROOT = E2eGitTree.copyOf('e2e-unconfigured-provider')
    private static final Path AUTO_ADVANCE_ROOT = E2eGitTree.copyOf('e2e-auto')

    /** @return the fixture's {@code .gnomish/} subdirectory, for direct {@code PipelineLoader} use */
    static Path gnomishDir() {
        Path dir = projectRoot().resolve('.gnomish')
        assert Files.isDirectory(dir)
        dir
    }
}
