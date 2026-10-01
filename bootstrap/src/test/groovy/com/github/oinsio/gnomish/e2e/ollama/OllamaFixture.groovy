package com.github.oinsio.gnomish.e2e.ollama

import com.github.oinsio.gnomish.e2e.E2eGitTree
import java.nio.file.Path

/**
 * Locates the {@code .gnomish-fixtures/ollama-e2e} resource tree (task 11.2, M1, D11): a
 * self-contained project directory with a single {@code write-file} stage whose only job is to
 * create {@code hello.txt}, graded by one judge vote whose acceptance criterion is exactly "the
 * file exists". Deliberately trivial (D11's risk note): a weak local Ollama model failing a
 * harder scenario would read as an adapter bug, not a model-capability gap. Mirrors {@link
 * com.github.oinsio.gnomish.e2e.E2eFixture} one-to-one for the plain E2E layer.
 *
 * <p>Implements M1, D11 of add-agent-executor.
 */
final class OllamaFixture {

    private OllamaFixture() {}

    /** A git working tree, so the spawned factory can register it (FR3 of add-project-registry). */
    private static final Path PROJECT_ROOT = E2eGitTree.copyOf('ollama-e2e')

    /**
     * @return the fixture project root ({@code --dir} target): a git working tree copied from the
     *     test classpath resource {@code /.gnomish-fixtures/ollama-e2e}
     */
    static Path projectRoot() {
        PROJECT_ROOT
    }

    /** @return the fixture's {@code .gnomish/} subdirectory, for direct {@code PipelineLoader} use */
    static Path gnomishDir() {
        projectRoot().resolve('.gnomish')
    }

    /** @return the path the {@code write-file} stage is instructed to create, inside the workspace */
    static Path expectedFile() {
        projectRoot().resolve('hello.txt')
    }
}
