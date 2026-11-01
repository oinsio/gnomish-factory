package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12 of define-executor-contract (design D19): a conformance case's working copy is made a git
 * repository through the factory's runner, so git's view of it can be taken — through the real
 * runner over a real directory, with the build's adversarial global configuration in force — and a
 * git that did not initialise it is refused naming how it ended.
 */
class RepositoryInitSpec extends Specification {

    @TempDir
    Path tempDir

    // FR12: an empty directory becomes an empty repository, git's status readable in it
    def "FR12: init makes an empty directory a git repository with nothing committed"() {
        given:
        def runner = new GitProcessRunner()
        AdversarialGitConfig.assertInEffect(runner)
        Path directory = Files.createDirectory(tempDir.resolve('transform'))

        when:
        new RepositoryInit(runner).init(directory)

        then:
        Files.isDirectory(directory.resolve('.git'))
        runner.run(directory, 'status', '--porcelain').succeeded()
        !runner.run(directory, 'rev-parse', '--verify', 'HEAD').succeeded()
    }

    // FR12: a git that refused, or ended without its own exit, is reported, never taken as done
    def "FR12: a git that #how is refused naming the directory and the end"() {
        given:
        def git = new ScriptedGit().answer(['init', '--quiet'], GitCommandResult.of(exit, '', 'fatal: cannot mkdir', termination))

        when:
        new RepositoryInit(git).init(tempDir)

        then:
        IllegalStateException refused = thrown()
        refused.message.contains(tempDir.toString())
        refused.message.contains(detail)
        git.calls()*.command() == [['init', '--quiet']]

        where:
        how || exit | termination | detail
        'exited 128' || 128 | Termination.EXITED | 'exited 128: fatal: cannot mkdir'
        'timed out' || 0 | Termination.TIMED_OUT | 'timed out'
    }
}
