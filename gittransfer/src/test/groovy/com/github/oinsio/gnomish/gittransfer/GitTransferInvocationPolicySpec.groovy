package com.github.oinsio.gnomish.gittransfer

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR4 of own-git-invocation-policy (design D2, D3): every transfer the owner builds — a fetch and a
 * harvest and a clone for each source kind, and origin's two pushes — layers on the invocation
 * policy. Its argv opens with the policy's pairs, every policy pair precedes the subcommand, no key
 * is set twice, and its environment removes the policy's variables; the value refuses an argv that
 * does not open with the pairs, so no transfer built any other way can exist.
 */
class GitTransferInvocationPolicySpec extends Specification {

    private static final Path SOURCE = Path.of('/factory-clone')
    private static final Path DESTINATION = Path.of('/task-volume/work')

    /** Every construction site of a transfer, by name. */
    private static final Map<String, Closure<GitTransfer>> SITES = [
        'an origin fetch': {
            GitTransfer.fetch(TransferSource.ORIGIN, new Refspec('+refs/heads/main:refs/remotes/origin/main'))
        },
        'an atomic origin fetch': {
            GitTransfer.fetch(TransferSource.ORIGIN, new Refspec('a:b'), new Refspec('+c:d'))
        },
        'a container harvest': {
            GitTransfer.harvest(new TransferSource.Container('ext::cmd %S'), 'gnomish/T-1', QuarantineRef.forTask('T-1'))
        },
        'a seed clone': {
            GitTransfer.clone(new TransferSource.SeedPath(SOURCE, DESTINATION))
        },
        'the closing push': {
            GitPush.closing('gnomish/T-1', RegisterRef.forTask('T-1'))
        },
        'the mid-round push': {
            GitPush.branchOnly('gnomish/T-1')
        },
    ]

    // FR4: the policy's pairs open the argv and so precede the subcommand; a key both sets name
    //     would carry two values, so no key may appear twice.
    // Scenario: git/git-invocation-policy / A transfer carries the invocation policy
    // Scenario: build-system/module-layering / The invocation policy is pure values in gittransfer
    def "FR4: #site carries every invocation pair before its subcommand, and no key twice"() {
        given:
        def transfer = SITES[site].call()
        def argv = transfer.argv()
        def subcommand = subcommandIndex(argv)

        expect: 'the argv opens with the policy pairs, in the policy order'
        argv.subList(0, GitInvocation.configPairs().size()) == GitInvocation.configPairs()

        and: 'the subcommand follows every pair'
        subcommand >= GitInvocation.configPairs().size()
        argv[subcommand] in ['fetch', 'clone', 'push']

        and: 'every configuration key is set once'
        def keys = pairs(argv.subList(0, subcommand)).collect {
            it.substring(0, it.indexOf('='))
        }
        keys.size() == keys.toSet().size()

        and: 'the policy removes its variables from the transfer environment too'
        GitInvocation.removedEnvironment().every {
            transfer.environment().get(it) == Optional.empty()
        }

        where:
        site << SITES.keySet().toList()
    }

    // FR4, design D2: the value itself refuses an argv that does not open with the policy pairs.
    def "FR4: a transfer whose argv does not open with the invocation pairs cannot be built: #label"() {
        when:
        new GitTransfer(argv, new LinkedHashMap<String, Optional<String>>())

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('invocation policy')

        where:
        label | argv
        'no pairs' | [
            'fetch',
            '--no-tags',
            'origin'
        ]
        'a pair missing' | GitInvocation.configPairs().drop(2) + ['fetch']
        'pairs after another' | ['-c', 'fetch.prune=false'] + GitInvocation.configPairs() + ['fetch']
        'empty' | []
    }

    def "FR4: a transfer whose argv opens with the invocation pairs is built as given"() {
        given:
        def argv = GitInvocation.configPairs() + ['fetch', 'origin']

        expect:
        new GitTransfer(argv, new LinkedHashMap<String, Optional<String>>()).argv() == argv
    }

    private static int subcommandIndex(List<String> argv) {
        int i = 0
        while (i + 1 <argv.size() && argv[i] == '-c') {
            i += 2
        }
        i
    }

    private static List<String> pairs(List<String> leading) {
        leading.collate(2).collect { it[1] }
    }
}
