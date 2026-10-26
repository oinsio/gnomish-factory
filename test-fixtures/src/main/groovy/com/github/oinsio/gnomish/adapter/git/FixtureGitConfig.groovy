package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path

/**
 * The test-only writer of a fixture repository's configuration: {@code git config <key> <value>}
 * and the {@code git remote} write forms, run as a raw {@code git} child rather than through
 * {@link GitProcessRunner}, whose untyped entry refuses every repository-configuration write
 * before launch (FR5, design D4 of own-git-invocation-policy). The factory writes nothing to a
 * clone's configuration; a spec that needs a remote, a committer identity or a broken URL in its
 * fixture sets it up here (design D6 of own-git-invocation-policy, "Fixture writes").
 *
 * <p>The child starts from the cleared test environment ({@link TestChildEnvironment#cleared}),
 * which keeps the build's git test configuration, so the write lands on the same global and
 * ref-format posture every other test git sees. Every write asserts success: a fixture whose
 * setup silently failed would make the spec over it pass for the wrong reason.
 *
 * <p>Reads stay where they are: {@code config --get}, {@code config --list} and
 * {@code remote get-url} pass the runner unrefused.
 *
 * <p>Implements FR5 of own-git-invocation-policy.
 */
final class FixtureGitConfig {

    private FixtureGitConfig() {}

    /** {@code git config <key> <value>} in {@code repo}'s local configuration. */
    static void set(Path repo, String key, String value) {
        git(repo, 'config', key, value)
    }

    /** {@code git config --add <key> <value>} in {@code repo}'s local configuration: one more value of a multi-valued key. */
    static void add(Path repo, String key, String value) {
        git(repo, 'config', '--add', key, value)
    }

    /** {@code git config --local --unset <key>} in {@code repo}: the key removed from its local configuration. */
    static void unset(Path repo, String key) {
        git(repo, 'config', '--local', '--unset', key)
    }

    /** {@code git remote add <name> <url>} in {@code repo}. */
    static void addRemote(Path repo, String name, String url) {
        git(repo, 'remote', 'add', name, url)
    }

    /** {@code git remote set-url <name> <url>} in {@code repo}. */
    static void setRemoteUrl(Path repo, String name, String url) {
        git(repo, 'remote', 'set-url', name, url)
    }

    /** {@code git remote remove <name>} in {@code repo}. */
    static void removeRemote(Path repo, String name) {
        git(repo, 'remote', 'remove', name)
    }

    private static void git(Path repo, String... args) {
        def command = ['git', *args.toList()]
        // No pipe to read: git's output goes to the test JVM's own standard output, where the
        // test report shows it, so this fixture never parks in a read of a child's pipe and needs
        // no exemption from ChildOutputOwnerGateSpec or RawCaptureGateSpec.
        def builder = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File('/dev/null')))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        TestChildEnvironment.cleared(builder)
        int exit = builder.start().waitFor()
        assert exit == 0: "${command.join(' ')} in ${repo} failed (${exit}); git's output is in the test's standard output"
    }
}
