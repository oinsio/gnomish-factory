package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.gittransfer.Refspec
import com.github.oinsio.gnomish.gittransfer.TransferSource
import java.nio.file.Path

/**
 * A spec's own transfers — the clone that stands up a second observer of a bare origin, the fetch
 * that seeds a tracking ref before the code under test runs — are test scaffolding, not factory
 * transfers, and so have no owner value: the owner ({@code GitTransfer} in {@code :gittransfer})
 * builds a single-branch, tagless clone of one seed path and a fetch of one refspec from
 * {@code origin} or a box, while a spec seeds whole repositories and fetches from arbitrary paths.
 * The runner refuses every transfer the owner did not build (FR8, design D5 of
 * own-git-transfer-argv), so the seeding forms here run git directly, outside
 * {@code GitProcessRunner}, under the test JVM's environment — the adversarial global
 * configuration included ({@code testing.md}, "Git fixtures are adversarial by default"), so a
 * seeding fetch spells its source in full ({@code refs/heads/…}) like every other fixture fetch.
 *
 * <p>Two rules keep this from becoming the escape hatch the runner closed. Only test sources can
 * reach it: {@code :test-fixtures} is on no production classpath, and the whole-tree gate over
 * {@code src/main} (task 6.1 of own-git-transfer-argv) lists this file as the one seeding site.
 * And a fetch from the clone's real {@code origin} takes {@link #fetchFromOrigin}, which goes
 * through the owner's value and the runner's typed entry — the factory path, taken wherever a spec
 * can take it, so the seeding forms stay for the shapes the owner does not produce.
 */
trait SeedTransferFixture {

    /**
     * Fetches one refspec from {@code origin} the way the factory does: the owner's value through
     * the runner's typed entry. The result is returned, not asserted, so a spec that expects the
     * fetch to fail (a branch not yet on the remote) reads the exit code itself.
     */
    GitCommandResult fetchFromOrigin(Path clone, String refspec) {
        new GitProcessRunner().run(clone, GitTransfer.fetch(TransferSource.ORIGIN, new Refspec(refspec)))
    }

    /**
     * {@code git clone <options> <source> <destination>} run directly, for a spec that needs a whole
     * second repository — every branch, tags included — rather than the owner's single-branch seed.
     * Asserts success and returns the destination.
     */
    Path seedClone(Path cwd, String source, Path destination, String... options) {
        def result = seedGit(cwd, ['clone'] + (options as List<String>) + [
            source,
            destination.toString()
        ])
        assert result.exitCode() == 0: "seed clone of ${source} failed: ${result.stderr()}"
        destination
    }

    /**
     * {@code git fetch <options> <source> <refspec>} run directly, for a source that is neither
     * {@code origin} nor a box: a sibling bare repository, a host-mode working copy standing in for
     * a container. Returned, not asserted, for the same reason as {@link #fetchFromOrigin}.
     */
    GitCommandResult seedFetch(Path repo, String source, String refspec, String... options) {
        seedGit(repo, ['fetch'] + (options as List<String>) + [source, refspec])
    }

    /**
     * {@code git submodule update --init} run directly, for a spec whose clone must hold an
     * initialized submodule — a clone of the submodule's remote, over the local-path transport git
     * closed by default in 2.38 ({@code protocol.file.allow}) and the owner produces no value for.
     * Asserts success.
     */
    void seedSubmodules(Path repo) {
        def result = seedGit(repo, [
            '-c',
            'protocol.file.allow=always',
            'submodule',
            'update',
            '--init'
        ])
        assert result.exitCode() == 0: "seeding submodules in ${repo} failed: ${result.stderr()}"
    }

    private GitCommandResult seedGit(Path cwd, List<String> args) {
        def builder = new ProcessBuilder(['git'] + args).directory(cwd.toFile())
        // Pinned as the runner pins it, so a spec classifying stderr (a non-fast-forward refusal)
        // reads git's English wording under any locale.
        builder.environment().put('LC_ALL', 'C')
        def process = builder.start()
        def stderr = new StringBuilder()
        def drain = Thread.startVirtualThread {
            stderr.append(process.errorStream.text)
        }
        String stdout = process.inputStream.text
        int exitCode = process.waitFor()
        drain.join()
        GitCommandResult.of(exitCode, stdout, stderr.toString())
    }
}
