package com.github.oinsio.gnomish.gitobjects

import spock.lang.Specification

/**
 * FR11 of add-base-ref-resolution: the record-level half of tree listing — the {@code ls-tree -z}
 * output shape and the mode-to-kind mapping — driven directly, because two of its answers cannot be
 * reached through {@link GitObjects#listTree}: an empty listing (git has no empty tree to address by
 * path) and a malformed record (real git never emits one, and the parser must refuse it rather than
 * invent an entry).
 */
class TreeListingSpec extends Specification {

    private static String record(String mode, String type, String name) {
        "${mode} ${type} 0123456789abcdef0123456789abcdef01234567\t${name}\0"
    }

    def "FR11: every git tree mode maps to its own kind (#mode)"() {
        expect:
        TreeListing.parse(record(mode, 'blob', 'entry')) == [new TreeEntry('entry', kind)]

        where:
        mode || kind
        '100644' || TreeEntry.Kind.FILE
        '100755' || TreeEntry.Kind.FILE
        '040000' || TreeEntry.Kind.DIRECTORY
        '120000' || TreeEntry.Kind.SYMLINK
        '160000' || TreeEntry.Kind.OTHER
        '123456' || TreeEntry.Kind.OTHER
    }

    def "FR11: consecutive records are parsed in order"() {
        expect:
        TreeListing.parse(record('100644', 'blob', 'a.yaml') + record('040000', 'tree', 'stages'))
                == [
                    new TreeEntry('a.yaml', TreeEntry.Kind.FILE),
                    new TreeEntry('stages', TreeEntry.Kind.DIRECTORY)
                ]
    }

    def "FR11: a name holding a space or a tab survives the split"() {
        expect:
        TreeListing.parse(record('100644', 'blob', name)) == [
            new TreeEntry(name, TreeEntry.Kind.FILE)
        ]

        where:
        name << [
            'two words.md',
            'tab\there.md'
        ]
    }

    def "FR11: empty output is an empty listing"() {
        expect:
        TreeListing.parse('').isEmpty()
    }

    def "FR11: a malformed record is refused, never guessed at (#malformed)"() {
        when:
        TreeListing.parse(malformed)

        then:
        def failure = thrown(GitObjectsException)
        failure.message.contains('ls-tree')

        where:
        malformed << [
            '100644 blob 0123456789abcdef0123456789abcdef01234567 name\0',
            "100644blob 0123456789abcdef0123456789abcdef01234567\tname\0",
            "100644 blob 0123456789abcdef0123456789abcdef01234567\t\0",
            "\tname 100644 blob\0",
        ]
    }
}
