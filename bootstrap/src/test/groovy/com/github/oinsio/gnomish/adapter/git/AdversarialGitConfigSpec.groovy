package com.github.oinsio.gnomish.adapter.git

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
class AdversarialGitConfigSpec extends Specification {

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
            it.startsWith('user.') || it.startsWith('core.') || it.startsWith('includeif.')
        }
    }

    def "FR12: a runner whose git points the variable elsewhere fails assertInEffect"() {
        given: 'a stand-in git that redirects the variable before delegating to the real one'
        Path standIn = tempDir.resolve('redirecting-git')
        standIn.toFile().text = """#!/bin/sh
${AdversarialGitConfig.VARIABLE}=/dev/null exec git "\$@"
"""
        standIn.toFile().executable = true

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
