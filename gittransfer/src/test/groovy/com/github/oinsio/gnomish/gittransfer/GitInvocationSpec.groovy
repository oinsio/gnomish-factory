package com.github.oinsio.gnomish.gittransfer

import spock.lang.Specification

/**
 * FR1, FR2, FR3, FR9 of own-git-invocation-policy (design D2, D3): the one owner of the invocation
 * policy. It builds the whole git command line — binary, the eight command-scope pairs and the
 * event-level hook switches, the caller's arguments — adds the pairs once even when the arguments already open with them, and
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

    /**
     * FR9, design D3: every hook event of githooks(5) in git 2.56.0 (the floor release, D11), client
     * and server side, in the page's order — read from the 2.56.0 manual page, spelled here as data so
     * the owner's list is pinned rather than echoed.
     */
    private static final List<String> HOOK_EVENTS = [
        'applypatch-msg',
        'pre-applypatch',
        'post-applypatch',
        'pre-commit',
        'pre-merge-commit',
        'prepare-commit-msg',
        'commit-msg',
        'post-commit',
        'pre-rebase',
        'post-checkout',
        'post-merge',
        'pre-push',
        'pre-receive',
        'update',
        'proc-receive',
        'post-receive',
        'post-update',
        'reference-transaction',
        'push-to-checkout',
        'pre-auto-gc',
        'post-rewrite',
        'sendemail-validate',
        'fsmonitor-watchman',
        'p4-changelist',
        'p4-prepare-changelist',
        'p4-post-changelist',
        'p4-pre-submit',
        'post-index-change',
    ]

    /** The whole policy: the eight pairs, then one event switch per hook event, in the list's order. */
    private static final List<String> POLICY = PAIRS + HOOK_EVENTS.collectMany {
        [
            '-c',
            "hook.${it}.enabled=false".toString()
        ]
    }

    // FR1, FR2: the policy opens with the eight pairs of design D3, in declared order.
    def "FR1, FR2: the policy pairs are the eight neutralizing keys of D3 in declared order, then the event switches"() {
        expect:
        GitInvocation.configPairs() == POLICY
    }

    // FR9, D3: one event switch per hook event of githooks(5) in 2.56.0, after the eight, in the list's order.
    def "FR9: after the eight pairs, the policy disables every hook event of git 2.56.0 by its event-level switch"() {
        expect:
        GitInvocation.configPairs().drop(PAIRS.size()) == HOOK_EVENTS.collectMany {
            [
                '-c',
                "hook.${it}.enabled=false".toString()
            ]
        }
    }

    // FR1, D2: binary, then the policy, then the caller's arguments unchanged.
    def "FR1: the command line is the binary, the policy pairs, then the caller's arguments unchanged"() {
        expect:
        GitInvocation.commandLine('/usr/bin/git', args) == ['/usr/bin/git'] + POLICY + args

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
        def args = POLICY + [
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
        keys.size() == 8 + HOOK_EVENTS.size()
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
