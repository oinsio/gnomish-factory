package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8 of add-doctor-command (design D6); UX2 of own-git-invocation-policy: the leftover
 * {@code core.hooksPath} line earlier factory versions wrote into a clone is named, with the one
 * command that removes it, only when the clone's <em>local</em> value ends in
 * {@code /gnomish-empty-hooks}; an unset key and an operator's own path are clear. The read goes
 * through the real runner over a real clone, so the invocation policy — whose command-scope
 * {@code core.hooksPath=/dev/null} an unscoped read would report — is in force, and the clone's
 * adversarial local posture (a planted hook directory: an operator's own path) is in place. The
 * reader writes nothing: the clone's {@code .git/config} is byte-identical afterwards.
 */
class HooksPathLeftoverSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()

    def setup() {
        AdversarialGitConfig.assertInEffect(runner)
    }

    def "FR8: the leftover line of an earlier version is reported with the unset fix, and nothing is written"() {
        given: 'a clone whose local configuration holds the line an earlier factory version wrote'
        Path clone = initWorkingRepo(tempDir)
        String leftover = clone.resolve('.git').resolve('gnomish-empty-hooks').toString()
        FixtureGitConfig.set(clone, 'core.hooksPath', leftover)
        byte[] before = configBytes(clone)

        when:
        def verdict = new HooksPathLeftover(runner).assess(clone)

        then:
        verdict instanceof HooksPathVerdict.Leftover
        (verdict as HooksPathVerdict.Leftover).value().contains(leftover)
        HooksPathVerdict.FIX == 'git config --local --unset core.hooksPath'

        and: 'the clone is untouched and the policy held'
        configBytes(clone) == before
        assertNothingPlantedRan(clone)
    }

    def "FR8: an unset local key is clear, although the policy's command scope sets one"() {
        given: 'a clone with no local core.hooksPath'
        Path clone = initWorkingRepo(tempDir)
        FixtureGitConfig.unset(clone, 'core.hooksPath')
        byte[] before = configBytes(clone)

        expect: 'an unscoped read would report the policy\'s own value — why the owner reads --local'
        runner.run(clone, 'config', '--get', 'core.hooksPath').stdout().contains('/dev/null')

        when:
        def verdict = new HooksPathLeftover(runner).assess(clone)

        then:
        verdict instanceof HooksPathVerdict.Clear
        configBytes(clone) == before
        assertNothingPlantedRan(clone)
    }

    def "FR8: an operator's own hooks path is clear: #description"() {
        given: 'a clone whose local hooks path the operator chose'
        Path clone = initWorkingRepo(tempDir)
        if (value != null) {
            FixtureGitConfig.set(clone, 'core.hooksPath', value)
        }
        byte[] before = configBytes(clone)

        when:
        def verdict = new HooksPathLeftover(runner).assess(clone)

        then:
        verdict instanceof HooksPathVerdict.Clear
        configBytes(clone) == before
        assertNothingPlantedRan(clone)

        where:
        description | value
        'the planted hook directory of the fixture' | null
        'a directory that merely contains the name' | '/srv/gnomish-empty-hooks/mine'
        'a name that ends without the separator' | '/srv/my-gnomish-empty-hooks'
    }

    def "FR8: a read git answered neither with a value nor with not-set is unread, quoting the failure: #termination #exitCode"() {
        given:
        def git = new ScriptedGit().answer([
            'config',
            '--local',
            '--get',
            'core.hooksPath'
        ],
        GitCommandResult.of(exitCode, '', 'fatal: bad config line 3', termination))

        when:
        def verdict = new HooksPathLeftover(git).assess(tempDir)

        then:
        verdict instanceof HooksPathVerdict.Unread
        (verdict as HooksPathVerdict.Unread).reason().forLog().contains(expected)
        git.calls()*.cwd == [tempDir]

        where:
        termination | exitCode || expected
        Termination.EXITED | 3 || 'exited 3: fatal: bad config line 3'
        Termination.TIMED_OUT | 1 || 'timed out'
        Termination.INTERRUPTED| 0 || 'was interrupted'
    }

    def "FR8: a not-set answer is clear and a value answer is judged by its trimmed tail, in process"() {
        given:
        def git = new ScriptedGit().answer([
            'config',
            '--local',
            '--get',
            'core.hooksPath'
        ], exitCode, stdout)

        expect:
        new HooksPathLeftover(git).assess(tempDir).class == expected

        where:
        exitCode | stdout || expected
        1 | '' || HooksPathVerdict.Clear
        0 | '/c/.git/gnomish-empty-hooks\n' || HooksPathVerdict.Leftover
        0 | '/c/.git/gnomish-empty-hooks/x\n' || HooksPathVerdict.Clear
    }

    private static byte[] configBytes(Path clone) {
        Files.readAllBytes(clone.resolve('.git').resolve('config'))
    }
}
