package com.github.oinsio.gnomish.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A {@code .gnomish-fixtures/<name>} resource tree copied under a temporary folder and made a git
 * working tree: the spawned factory works only in a registered clone, and only a git working tree
 * registers (FR2, FR3 of add-project-registry). The resource itself stays as the build copied it.
 * Shared by {@link E2eFixture}, the Ollama fixture and the paid smoke, which each drive the packaged
 * jar against a tree of their own.
 *
 * <p>Implements FR3 of add-project-registry (test support).
 */
final class E2eGitTree {

    private E2eGitTree() {}

    /**
     * @param name the folder under {@code /.gnomish-fixtures} on the test classpath
     * @return a fresh copy of that tree, initialised as a git working tree
     */
    static Path copyOf(String name) {
        Path resource = Path.of(E2eGitTree.getResource("/.gnomish-fixtures/${name}").toURI())
        Path copy = Files.createTempDirectory('gnomish-e2e-clone').resolve(name)
        Files.walk(resource).withCloseable { paths ->
            paths.forEach { Path source ->
                Files.copy(source, copy.resolve(resource.relativize(source).toString()))
            }
        }
        Process init = new ProcessBuilder('git', 'init', '--quiet', copy.toString()).redirectErrorStream(true).start()
        assert init.waitFor(60, TimeUnit.SECONDS) && init.exitValue() == 0: "git init failed: ${init.inputStream.text}"
        copy
    }
}
