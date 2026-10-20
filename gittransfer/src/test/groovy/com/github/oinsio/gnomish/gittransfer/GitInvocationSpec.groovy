package com.github.oinsio.gnomish.gittransfer

import spock.lang.Specification

/**
 * FR1, FR2, FR3 of own-git-invocation-policy (design D2, D3): the one owner of the invocation
 * policy. It builds the whole git command line — binary, the eight command-scope pairs, the
 * caller's arguments — adds the pairs once even when the arguments already open with them, and
 * names the two inherited configuration variables every factory git process has removed. The
 * expected values are spelled here in full, so a pair that appears, disappears, changes value or
 * moves is a red spec.
 */
class GitInvocationSpec extends Specification {

    private static final List<String> PAIRS = [
        '-c',
        'core.hooksPath=/dev/null',
        '-c',
        'commit.gpgSign=false',
        '-c',
        'tag.gpgSign=false',
        '-c',
        'core.fsmonitor=false',
        '-c',
        'branch.autoSetupMerge=false',
        '-c',
        'maintenance.auto=false',
        '-c',
        'gc.auto=0',
        '-c',
        'core.useReplaceRefs=false',
    ]

    // FR1, FR2: the policy is exactly the eight pairs of design D3, in declared order.
    def "FR1, FR2: the policy pairs are the eight neutralizing keys of D3 in declared order"() {
        expect:
        GitInvocation.configPairs() == PAIRS
    }

    // FR1, D2: binary, then the policy, then the caller's arguments unchanged.
    def "FR1: the command line is the binary, the policy pairs, then the caller's arguments unchanged"() {
        expect:
        GitInvocation.commandLine('/usr/bin/git', args) == ['/usr/bin/git'] + PAIRS + args

        where:
        args << [
            ['commit', '-m', 'state'],
            ['update-ref', '--stdin'],
            ['-c', 'user.name=x', 'commit'],
            [],
        ]
    }

    // D2: a transfer's argv already opens with the pairs; the owner adds no second copy.
    def "D2: an argv that already opens with the policy pairs carries them once"() {
        given:
        def args = PAIRS + [
            'fetch',
            '--no-tags',
            '--end-of-options',
            'origin'
        ]

        expect:
        GitInvocation.commandLine('git', args) == ['git'] + args
    }

    // FR3: inherited per-process configuration is stripped for every command.
    def "FR3: the environment-removal set is exactly the two inherited configuration variables"() {
        expect:
        GitInvocation.removedEnvironment() == [
            'GIT_CONFIG_PARAMETERS',
            'GIT_CONFIG_COUNT'
        ]
    }

    // Scenario: git/git-invocation-policy / The policy sets no credential or transport key
    def "NFR-S2: no policy pair names a credential, SSH command or URL rewrite key"() {
        given:
        def keys = GitInvocation.configPairs().findAll { it != '-c' }

        expect:
        keys.size() == 8
        keys.every { pair ->
            !pair.startsWith('credential.') && !pair.startsWith('core.sshCommand') && !pair.startsWith('url.')
        }
    }

    // D2: the binary is the one element the owner cannot default.
    def "a blank git binary is refused"() {
        when:
        GitInvocation.commandLine(binary, ['status'])

        then:
        thrown(IllegalArgumentException)

        where:
        binary << ['', '   ']
    }

    // D2: the returned command line is a value no launcher can amend.
    def "the command line, the pairs and the removal set are read-only"() {
        when:
        list.add('x')

        then:
        thrown(UnsupportedOperationException)

        where:
        list << [
            GitInvocation.commandLine('git', ['status']),
            GitInvocation.configPairs(),
            GitInvocation.removedEnvironment(),
        ]
    }
}
