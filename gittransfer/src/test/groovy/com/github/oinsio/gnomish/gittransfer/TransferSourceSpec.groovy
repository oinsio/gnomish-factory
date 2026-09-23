package com.github.oinsio.gnomish.gittransfer

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR4, FR6 of own-git-transfer-argv: a transfer source is one of a closed set of kinds, and
 * each kind carries its protocol allowlist and its configuration-isolation shape as values — the
 * per-source rows of design D2's table — so the owner reads policy from the source rather than
 * from a caller's flag list.
 */
class TransferSourceSpec extends Specification {

    private static final Path SOURCE = Path.of('/factory-clone')
    private static final Path DESTINATION = Path.of('/task-volume/work')

    // FR4: the allowlist is the whole protocol policy, one closed string per kind.
    def "each source kind carries its protocol allowlist"() {
        expect:
        source.protocolAllowlist() == allowlist

        where:
        source || allowlist
        TransferSource.ORIGIN || 'https:http:ssh:file'
        new TransferSource.Container('ext::docker exec -i b %S') || 'ext'
        new TransferSource.SeedPath(SOURCE, DESTINATION) || 'file'
    }

    // FR6: origin keeps the operator's configuration; a container reads none; the seed helper
    //     reads none but its own throwaway safe.directory file.
    def "each source kind carries its configuration-isolation shape"() {
        expect:
        source.configurationIsolation() == isolation

        where:
        source || isolation
        TransferSource.ORIGIN || [:]
        new TransferSource.Container('ext::docker exec -i b %S') || [
            GIT_CONFIG_GLOBAL: Optional.of('/dev/null'),
            GIT_CONFIG_SYSTEM: Optional.of('/dev/null'),
            XDG_CONFIG_HOME: Optional.of('/dev/null'),
        ]
        new TransferSource.SeedPath(SOURCE, DESTINATION) || [
            GIT_CONFIG_GLOBAL: Optional.of(TransferSource.SeedPath.SAFE_DIRECTORY_CONFIG),
            GIT_CONFIG_SYSTEM: Optional.of('/dev/null'),
            XDG_CONFIG_HOME: Optional.of('/dev/null'),
        ]
    }

    // FR6: the seed helper's global file is named by the leaf, so the script's own export and the
    //     owner's environment cannot disagree (design D6).
    def "the seed helper's throwaway global file is a fixed path"() {
        expect:
        TransferSource.SeedPath.SAFE_DIRECTORY_CONFIG == '/tmp/gnomish-seed-gitconfig'
    }

    // FR1: the origin source is one value — two references to it compare equal.
    def "origin is a singleton value"() {
        expect:
        TransferSource.ORIGIN == new TransferSource.Origin()
        TransferSource.ORIGIN.hashCode() == new TransferSource.Origin().hashCode()
    }

    // FR4: a container source is reached over ext:: and nothing else; any other spelling would
    //     be refused by git under the allowlist, so the type refuses it first.
    def "a container source must be an ext:: URL"() {
        when:
        new TransferSource.Container(url)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('ext::')

        where:
        url << [
            '',
            ' ',
            'https://example.invalid/repo.git',
            'ext:: ',
            'EXT::docker exec'
        ]
    }

    def "a container source keeps its URL"() {
        expect:
        new TransferSource.Container('ext::docker exec -i box %S').extUrl() == 'ext::docker exec -i box %S'
    }

    def "a seed path keeps its source and destination"() {
        given:
        def seed = new TransferSource.SeedPath(SOURCE, DESTINATION)

        expect:
        seed.source() == SOURCE
        seed.destination() == DESTINATION
    }

    def "a seed path refuses a destination equal to its source"() {
        when:
        new TransferSource.SeedPath(SOURCE, SOURCE)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('destination')
    }

    def "a missing component is refused outright"() {
        when:
        construct()

        then:
        thrown(NullPointerException)

        where:
        construct << [
            { new TransferSource.Container(null) },
            { new TransferSource.SeedPath(null, DESTINATION) },
            { new TransferSource.SeedPath(SOURCE, null) },
        ]
    }
}
