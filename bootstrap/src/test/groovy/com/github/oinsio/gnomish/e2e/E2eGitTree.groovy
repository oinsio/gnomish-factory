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
 * <p>A git-mode run needs more than a working tree: a base on a remote to branch from and push to.
 * {@link #publishedCopyOf} gives a copy committed on {@code main} and pushed to a bare
 * {@code origin} beside it, bound to the host environment — the shape the git-mode journeys drive.
 *
 * <p>Implements FR3 of add-project-registry (test support); M3 of make-run-headless.
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

    /**
     * {@link #copyOf}, published to a bare {@code origin} beside the copy and registered in the
     * harness home with the host binding, so a git-mode {@code run} — fresh or {@code --resume} —
     * works in a worktree of its own and never needs Docker.
     *
     * @param name the folder under {@code /.gnomish-fixtures} on the test classpath
     * @return the clone a git-mode run passes as {@code --dir}
     */
    static Path publishedCopyOf(String name) {
        Path clone = copyOf(name)
        publish(clone, clone.resolveSibling('origin.git'))
        E2eProcessHarness.projectConfig(clone, 'factory:\n  bindings:\n    default: host\n')
        clone
    }

    /** Commits the tree on {@code main} and pushes it to a bare {@code origin}. */
    static void publish(Path clone, Path origin) {
        git(origin.parent, 'init', '--quiet', '--bare', '--initial-branch=main', origin.toString())
        git(clone, 'checkout', '--quiet', '-b', 'main')
        git(clone, 'add', '--all')
        git(clone, 'commit', '--quiet', '-m', 'fixture')
        git(clone, 'remote', 'add', 'origin', origin.toString())
        git(clone, 'push', '--quiet', 'origin', 'refs/heads/main:refs/heads/main')
        git(clone, 'fetch', '--quiet', 'origin', 'refs/heads/main:refs/remotes/origin/main')
    }

    /** @return the trimmed stdout of {@code git -C dir args...}, asserting it exited 0 */
    static String git(Path dir, String... args) {
        Process process = new ProcessBuilder(['git', '-C', dir.toString()] + args.toList()).redirectErrorStream(true).start()
        String output = process.inputStream.text
        assert process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0: "git ${args.join(' ')}: ${output}"
        output.trim()
    }
}
