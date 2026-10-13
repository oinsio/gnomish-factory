package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12, NFR-S2 of own-git-transfer-argv (design D11): the whole test build runs git under the
 * committed adversarial global configuration, and {@link AdversarialGitConfig#assertInEffect}
 * tells a run where it is not in force from one where it is.
 *
 * <p>Lives in {@code :bootstrap} because the claim is about the build, not about one module: the
 * same {@code test-conventions} wiring reaches every Test task, and this module is where the
 * repository root is known, so the file git lists can be compared with the committed one.
 */
class AdversarialGitConfigSpec extends Specification implements SeedTransferFixture {

    @TempDir
    Path tempDir

    GitProcessRunner runner = new GitProcessRunner()

    def "FR12: the probe answers through the runner, so assertInEffect passes under the build"() {
        when:
        AdversarialGitConfig.assertInEffect(runner)

        then:
        noExceptionThrown()

        and: 'the answer is the probe line and nothing else'
        runner.run(tempDir, AdversarialGitConfig.PROBE_ALIAS).stdout().forParsing().trim() ==
                AdversarialGitConfig.PROBE_LINE
    }

    def "NFR-S2: git's global configuration is the committed file and nothing of the developer's"() {
        given: 'the file the build handed git is the committed one'
        Path handed = AdversarialGitConfig.path()
        handed.toRealPath() == RepoSourceTree.repoRoot().resolve(AdversarialGitConfig.RELATIVE_PATH).toRealPath()

        and: 'the classpath copy and the file on disk are the same text'
        Files.readString(handed) == AdversarialGitConfig.contents()

        when: 'git lists its global configuration through the runner'
        def global = listed('--global')

        then: 'it lists exactly the committed file'
        global == listed("--file=${handed}")

        and: 'every key it lists is one the fixture documents — none of an operator\'s own'
        global.keySet() == AdversarialGitConfig.KEYS
        !global.keySet().any {
            it.startsWith('core.') || it.startsWith('includeif.')
        }

        and: 'the identity is the fixture\'s, not the developer\'s'
        global['user.name'] == AdversarialGitConfig.IDENTITY_NAME
        global['user.email'] == AdversarialGitConfig.IDENTITY_EMAIL
    }

    def "NFR-S2: a production-style commit succeeds with no identity git could guess"() {
        given: 'a repository, and git forbidden to guess an identity from the account and host'
        runner.run(tempDir, 'init', '-q').exitCode() == 0
        tempDir.resolve('f').toFile().text = 'x'
        runner.run(tempDir, 'add', 'f').exitCode() == 0

        when: 'git commits without -c user.*, as the production adapters do'
        def result = runner.run(tempDir, '-c', 'user.useConfigOnly=true', 'commit', '-q', '-m', 'm')

        then: 'the fixture identity carries it, as it does on a CI runner'
        result.exitCode() == 0
        runner.run(tempDir, 'log', '-1', '--format=%an <%ae>').stdout().forParsing().trim() ==
                "${AdversarialGitConfig.IDENTITY_NAME} <${AdversarialGitConfig.IDENTITY_EMAIL}>".toString()
    }

    def "F122, F135: every repository the test build creates is on the reftable ref backend"() {
        given: 'a bare origin, a working repo with one commit pushed to it, a clone and a worktree'
        Path origin = tempDir.resolve('origin.git')
        Path work = tempDir.resolve('work')
        Path clone = tempDir.resolve('clone')
        Path worktree = tempDir.resolve('worktree')
        [origin, work]*.toFile()*.mkdirs()
        assert runner.run(origin, 'init', '-q', '--bare').exitCode() == 0
        assert runner.run(work, 'init', '-q').exitCode() == 0
        assert runner.run(work, 'commit', '-q', '--allow-empty', '-m', 'm').exitCode() == 0
        assert runner.run(work, 'push', '-q', origin.toString(), 'HEAD:refs/heads/main').exitCode() == 0
        seedClone(tempDir, origin.toString(), clone, '-q', '--branch', 'main')
        assert runner.run(clone, 'worktree', 'add', '-q', worktree.toString(), '-b', 'task').exitCode() == 0

        expect: 'the build handed git the backend the fixture names (the two ends of the sync pair agree)'
        System.getenv(AdversarialGitConfig.REF_FORMAT_VARIABLE) == AdversarialGitConfig.REF_FORMAT

        and: 'each reports it, the backend the factory clone must be on'
        [origin, work, clone, worktree].every {
            runner.run(it, 'rev-parse', '--show-ref-format').stdout().forParsing().trim() ==
            AdversarialGitConfig.REF_FORMAT
        }
    }

    def "FR12: a runner whose git points the variable elsewhere fails assertInEffect"() {
        given: 'a stand-in git that redirects the variable before delegating to the real one'
        Path standIn = StandIn.git('redirect-global-config')

        when:
        AdversarialGitConfig.assertInEffect(new GitProcessRunner(standIn.toString()))

        then:
        def e = thrown(AssertionError)
        e.message.contains('not in force')
        e.message.contains(AdversarialGitConfig.VARIABLE)
    }

    /** {@code git config <scope> --list} as a key → value map, through the runner. */
    private Map<String, String> listed(String scope) {
        def result = runner.run(tempDir, 'config', scope, '--list')
        assert result.exitCode() == 0
        result.stdout().forParsing().readLines().findAll {
            it
        }.collectEntries { line ->
            int eq = line.indexOf('=')
            [
                line.substring(0, eq),
                line.substring(eq + 1)
            ]
        }
    }
}
