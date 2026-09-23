package com.github.oinsio.gnomish.gittransfer

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR3, FR4, FR5, FR6, FR7 of own-git-transfer-argv: the owner turns a source kind and a
 * refspec into the whole argv and environment of a transfer. One feature per source kind pins
 * the complete value — the common set of design D2 in its two halves, the per-source row, and
 * nothing else — so a flag that appears, disappears or moves is a red spec, not a review item.
 */
class GitTransferSpec extends Specification {

    private static final Path SOURCE = Path.of('/factory-clone')
    private static final Path DESTINATION = Path.of('/task-volume/work')

    /** The everywhere half of design D2's common set: `-c` pairs that precede the subcommand. */
    private static final List<String> CONFIG_PAIRS = [
        '-c',
        'fetch.recurseSubmodules=no',
        '-c',
        'submodule.recurse=false',
        '-c',
        'fetch.prune=false',
        '-c',
        'maintenance.auto=false',
        '-c',
        'gc.auto=0',
        '-c',
        'fetch.fsckObjects=true',
        '-c',
        'transfer.fsckObjects=true',
        '-c',
        'fetch.fsck.badTimezone=ignore',
        '-c',
        'fetch.fsck.missingSpaceBeforeDate=ignore',
        '-c',
        'fetch.fsck.zeroPaddedFilemode=ignore',
    ]

    /** The everywhere half of the environment: inherited overrides that no transfer may see. */
    private static final Map<String, Optional<String>> STRIPPED = [
        GIT_CONFIG_PARAMETERS: Optional.<String> empty(),
        GIT_CONFIG_COUNT: Optional.<String> empty(),
        GIT_OBJECT_DIRECTORY: Optional.<String> empty(),
        GIT_ALTERNATE_OBJECT_DIRECTORIES: Optional.<String> empty(),
        GIT_WORK_TREE: Optional.<String> empty(),
        GIT_INDEX_FILE: Optional.<String> empty(),
    ]

    // FR3, FR4, FR5, FR6: a named remote gets the fetch-only half in full — `--refmap=` included —
    //     and keeps the operator's configuration, re-asserted by the `-c` pairs.
    def "an origin fetch is the common set, the fetch-only half, and the operator's configuration"() {
        when:
        def transfer = GitTransfer.fetch(TransferSource.ORIGIN, new Refspec('+refs/heads/main:refs/remotes/origin/main'))

        then:
        transfer.argv() == CONFIG_PAIRS + [
            'fetch',
            '--no-tags',
            '--no-recurse-submodules',
            '--no-write-fetch-head',
            '--refmap=',
            '--end-of-options',
            'origin',
            '+refs/heads/main:refs/remotes/origin/main',
        ]

        and:
        transfer.environment() == STRIPPED + [GIT_ALLOW_PROTOCOL: Optional.of('https:http:ssh:file')]
    }

    // FR3, FR4, FR5, FR6: a container fetch names its URL in place of the remote, so no refmap
    //     applies; it reads no operator configuration at all.
    def "a container fetch is the common set, the fetch-only half without a refmap, and full isolation"() {
        when:
        def transfer = GitTransfer.fetch(
                new TransferSource.Container('ext::docker exec -i box %S'),
                new Refspec('refs/heads/gnomish/T-1:refs/heads/gnomish/T-1'))

        then:
        transfer.argv() == CONFIG_PAIRS + [
            'fetch',
            '--no-tags',
            '--no-recurse-submodules',
            '--no-write-fetch-head',
            '--end-of-options',
            'ext::docker exec -i box %S',
            'refs/heads/gnomish/T-1:refs/heads/gnomish/T-1',
        ]

        and:
        transfer.environment() == STRIPPED + [
            GIT_ALLOW_PROTOCOL: Optional.of('ext'),
            GIT_CONFIG_GLOBAL: Optional.of('/dev/null'),
            GIT_CONFIG_SYSTEM: Optional.of('/dev/null'),
            XDG_CONFIG_HOME: Optional.of('/dev/null'),
        ]
    }

    // FR3, FR6, FR7: the seed clone takes git's transport path, one branch, no tags, and carries
    //     the branch as the leaf's placeholder; neither fetch-only flag appears (clone rejects
    //     `--no-write-fetch-head` and has no remote for `--refmap=`).
    def "a seed clone is the common set, the clone flags, and isolation but for the safe.directory file"() {
        when:
        def transfer = GitTransfer.clone(new TransferSource.SeedPath(SOURCE, DESTINATION))

        then:
        transfer.argv() == CONFIG_PAIRS + [
            'clone',
            '--no-local',
            '--no-hardlinks',
            '--single-branch',
            '--no-tags',
            '--no-recurse-submodules',
            '--branch',
            GitTransfer.BRANCH_PARAMETER,
            '--end-of-options',
            '/factory-clone',
            '/task-volume/work',
        ]

        and:
        transfer.environment() == STRIPPED + [
            GIT_ALLOW_PROTOCOL: Optional.of('file'),
            GIT_CONFIG_GLOBAL: Optional.of('/tmp/gnomish-seed-gitconfig'),
            GIT_CONFIG_SYSTEM: Optional.of('/dev/null'),
            XDG_CONFIG_HOME: Optional.of('/dev/null'),
        ]
    }

    // FR7, design D6: the placeholder is one argv element the caller substitutes; it is not a
    //     ref name git could accept, so a value that leaks unsubstituted is refused, never cloned.
    def "the branch placeholder is one element that no ref name can equal"() {
        expect:
        GitTransfer.BRANCH_PARAMETER.contains(':')
        !GitTransfer.BRANCH_PARAMETER.startsWith('-')
    }

    // FR4: the allowlist is the whole protocol policy — nothing beside it on any kind.
    def "no source carries a protocol level key or a GIT_PROTOCOL_FROM_USER entry"() {
        expect:
        transfer.argv().every { !it.startsWith('protocol.') }
        !transfer.environment().containsKey('GIT_PROTOCOL_FROM_USER')

        where:
        transfer << [
            GitTransfer.fetch(TransferSource.ORIGIN, new Refspec('a:b')),
            GitTransfer.fetch(new TransferSource.Container('ext::cmd %S'), new Refspec('a:b')),
            GitTransfer.clone(new TransferSource.SeedPath(SOURCE, DESTINATION)),
        ]
    }

    // FR1: the value is what the media consume — both halves are read-only, and the environment
    //     keeps the order it was built in so a script renders it deterministically.
    def "the value is immutable and its environment is ordered"() {
        given:
        def transfer = GitTransfer.clone(new TransferSource.SeedPath(SOURCE, DESTINATION))

        when:
        transfer.argv().add('--depth=1')

        then:
        thrown(UnsupportedOperationException)

        when:
        transfer.environment().put('GIT_DIR', Optional.of('/elsewhere'))

        then:
        thrown(UnsupportedOperationException)

        and:
        transfer.environment().keySet().toList() ==
                STRIPPED.keySet().toList() + [
                    'GIT_ALLOW_PROTOCOL',
                    'GIT_CONFIG_GLOBAL',
                    'GIT_CONFIG_SYSTEM',
                    'XDG_CONFIG_HOME'
                ]
    }

    def "a missing input is refused outright"() {
        when:
        construct()

        then:
        thrown(NullPointerException)

        where:
        construct << [
            { GitTransfer.fetch(null, new Refspec('a:b')) },
            { GitTransfer.fetch(TransferSource.ORIGIN, null) },
            { GitTransfer.clone(null) },
        ]
    }
}
