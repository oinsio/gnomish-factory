package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path

/**
 * The child-JVM half of {@link GitProcessRunnerInheritedConfigSpec}: one commit through the real
 * {@link GitProcessRunner}, run in a JVM whose own environment the spec composed — the only way to
 * plant a variable in the inherited environment of the runner's {@link ProcessBuilder}, since a
 * running JVM cannot change its own (the precedent is {@code FixtureBoxRoundProcess} in
 * {@code :bootstrap}).
 *
 * <p>Argument: the working repository, with its change already staged. Prints the planted key, so
 * the spec can prove the plant reached this JVM, then the commit's exit code and stderr.
 *
 * <p>FR3 of own-git-invocation-policy.
 */
final class InheritedConfigCommitProcess {

    /** The line this process prints first, naming the inherited key it was started with. */
    static final String PLANTED_PREFIX = 'inherited GIT_CONFIG_KEY_0='

    /** The line carrying the commit's exit code. */
    static final String EXIT_PREFIX = 'commit exit='

    private InheritedConfigCommitProcess() {}

    static void main(String[] args) {
        println(PLANTED_PREFIX + System.getenv('GIT_CONFIG_KEY_0'))
        def result = new GitProcessRunner()
                .run(Path.of(args[0]), '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'inherited')
        println(EXIT_PREFIX + result.exitCode())
        println(result.stderr().forParsing())
    }
}
