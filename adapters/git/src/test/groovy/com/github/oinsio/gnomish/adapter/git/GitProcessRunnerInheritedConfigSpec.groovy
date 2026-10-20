package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3 of own-git-invocation-policy (design D2): an inherited per-process configuration does not
 * reach a factory git process, whatever the subcommand. The vector is a {@code GIT_CONFIG_COUNT}
 * pair in the factory's own environment naming a hook directory, observed with real git: a
 * {@code pre-commit} hook that refuses every commit it runs for.
 *
 * <p>The runner composes its child's environment from the JVM's, and a running JVM cannot change
 * its own, so the factory process is a child JVM ({@link InheritedConfigCommitProcess}) whose
 * environment this spec composes through {@link TestChildEnvironment}, plus the planted pair.
 */
class GitProcessRunnerInheritedConfigSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    // Scenario: git/git-invocation-policy / An inherited configuration pair does not reach a commit
    def "FR3: a commit by a factory started with an inherited hook directory runs no hook"() {
        given: 'a working repository with a staged change'
        Path repo = initWorkingRepo(tempDir)
        Files.writeString(repo.resolve('a.txt'), 'a')
        assert gitExitCode(repo, 'add', 'a.txt') == 0

        and: 'a hook directory whose pre-commit refuses every commit'
        Path hooks = Files.createDirectories(tempDir.resolve('planted-hooks'))
        StandIn.link(hooks.resolve('pre-commit'), 'hook-refuse')

        and: 'the plant is live: a git started under the inherited pair runs the hook and is refused'
        def plain = new ProcessBuilder('git', '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'plain')
                .directory(repo.toFile())
                .redirectErrorStream(true)
        planted(plain, hooks)
        def control = plain.start()
        control.inputStream.text
        assert control.waitFor(1, TimeUnit.MINUTES)
        assert control.exitValue() != 0

        when: 'a factory process started with the same pair commits through the runner'
        def builder = new ProcessBuilder(
                ProcessHandle.current().info().command().orElseThrow(),
                '-cp',
                System.getProperty('java.class.path'),
                InheritedConfigCommitProcess.getName(),
                repo.toString()).redirectErrorStream(true)
        planted(builder, hooks)
        def process = builder.start()
        String output = process.inputStream.text
        boolean exited = process.waitFor(2, TimeUnit.MINUTES)

        then: 'the factory JVM really held the planted pair'
        exited
        process.exitValue() == 0
        output.readLines().first() == InheritedConfigCommitProcess.PLANTED_PREFIX + 'core.hooksPath'

        and: 'the hook never ran: the commit landed'
        output.readLines()[1] == InheritedConfigCommitProcess.EXIT_PREFIX + '0'
        gitOutput(repo, 'log', '-1', '--format=%s') == 'inherited'
    }

    /** Clears {@code builder}'s environment to the test set and plants the inherited hook pair. */
    private static void planted(ProcessBuilder builder, Path hooks) {
        TestChildEnvironment.cleared(builder).putAll([
            GIT_CONFIG_COUNT: '1',
            GIT_CONFIG_KEY_0: 'core.hooksPath',
            GIT_CONFIG_VALUE_0: hooks.toString(),
        ])
    }
}
