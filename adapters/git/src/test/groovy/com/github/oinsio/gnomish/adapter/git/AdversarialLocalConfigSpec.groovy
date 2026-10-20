package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8 of own-git-invocation-policy (design D6): the shared clone fixture's adversarial local
 * configuration is in force on every working repository {@link BareGitRepoFixture#initWorkingRepo}
 * builds, each planted program fires when git runs without the factory's invocation policy, the
 * factory runner's policy keeps every one of them silent, and {@link
 * BareGitRepoFixture#initPlainWorkingRepo} carries none of it.
 */
class AdversarialLocalConfigSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    // Scenario: git/git-invocation-policy / The posture is in force
    def "FR8: a working repository from the shared fixture holds every planted vector in its local configuration"() {
        when:
        def repo = initWorkingRepo(tempDir)

        then: 'each planted key reads back from the local configuration file'
        AdversarialLocalConfig.expectedConfig(repo).each { key, value ->
            assert gitOutput(repo, 'config', '--local', '--get', key) == value
        }

        and: 'each planted program is a link to the committed stand-in, and none has run yet'
        AdversarialLocalConfig.programs(repo).keySet() == [
            'pre-commit',
            'post-checkout',
            'reference-transaction',
            'gpg',
            'fsmonitor'
        ] as Set
        AdversarialLocalConfig.programs(repo).values().every {
            Files.isSymbolicLink(it) && Files.isExecutable(it)
        }
        AdversarialLocalConfig.ran(repo).isEmpty()
    }

    def "FR8: git through the factory runner's policy commits, checks out and moves refs with no planted program running"() {
        given:
        def repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'one'

        when:
        commitAll(repo)
        gitOutput(repo, 'checkout', '-q', '-b', 'side')
        gitOutput(repo, 'update-ref', 'refs/heads/other', 'HEAD')
        gitOutput(repo, 'status', '--porcelain')

        then:
        noExceptionThrown()
        assertNothingPlantedRan(repo)
    }

    def "FR8: raw git without the policy runs the planted #program, fails, and assertNothingPlantedRan names it"() {
        given:
        def repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'one'
        commitAll(repo)
        new File(repo.toFile(), 'a.txt').text = 'changed'

        when:
        int exit = rawGit(repo, args)

        then: 'the planted program left its marker'
        program in AdversarialLocalConfig.ran(repo)

        and: 'a refusing program fails the operation loudly (the fsmonitor only degrades git to a full scan)'
        failsLoudly == (exit != 0)

        when:
        assertNothingPlantedRan(repo)

        then:
        def error = thrown(AssertionError)
        error.message.contains(program)

        where:
        program | failsLoudly | args
        'pre-commit' | true | [
            'commit',
            '-q',
            '--allow-empty',
            '-m',
            'raw'
        ]
        'gpg' | true | [
            'commit',
            '-q',
            '--allow-empty',
            '--no-verify',
            '-m',
            'raw'
        ]
        'post-checkout' | true | ['checkout', '--', 'a.txt']
        'reference-transaction' | true | [
            'update-ref',
            'refs/heads/other',
            'HEAD'
        ]
        'fsmonitor' | false | ['status', '--porcelain']
    }

    // Scenario: git/git-invocation-policy / The posture is in force
    def "FR8: the plain variant, asked for by name, carries none of the posture"() {
        when:
        def repo = initPlainWorkingRepo(tempDir)

        then:
        AdversarialLocalConfig.expectedConfig(repo).keySet().every { key ->
            gitExitCode(repo, 'config', '--local', '--get', key) == 1
        }
        !Files.exists(repo.resolve('.git').resolve('gnomish-planted'))
        AdversarialLocalConfig.ran(repo).isEmpty()
    }

    /**
     * Runs {@code git} in {@code repo} as a raw child — no invocation policy, the cleared test
     * environment — the way a path that bypasses the factory runner would.
     */
    private static int rawGit(Path repo, List<String> args) {
        def builder = new ProcessBuilder(['git', *args])
        .directory(repo.toFile())
        .redirectInput(ProcessBuilder.Redirect.from(new File('/dev/null')))
        .redirectErrorStream(true)
        TestChildEnvironment.cleared(builder)
        def process = builder.start()
        process.inputStream.getText('UTF-8')
        process.waitFor()
    }
}
