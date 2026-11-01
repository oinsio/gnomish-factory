package com.github.oinsio.gnomish.adapter.agent.program

import spock.lang.Specification

/**
 * {@link StatusEntries} over git's {@code status --porcelain=v2 -z --branch} records (FR11, NFR-S4 of
 * define-executor-contract): which records a view keeps, and the path each differing record names.
 */
class StatusEntriesSpec extends Specification {

    static final String OID = '# branch.oid 1111111111111111111111111111111111111111'

    // FR11: of the headers only HEAD's commit and branch are compared; the upstream ones move with a fetch
    def "FR11: a view keeps every entry and the HEAD headers, and drops the upstream headers and empty records"() {
        given:
        String porcelain = [
            OID,
            '# branch.head main',
            '# branch.upstream origin/main',
            '# branch.ab +0 -0',
            '? notes.txt',
            ''
        ].join('\u0000')

        expect:
        StatusEntries.of(porcelain) as List == [
            OID,
            '# branch.head main',
            '? notes.txt'
        ]
    }

    // FR11: a record the run added comes first, then one it removed, each path named once
    def "FR11: the changed paths are what the second view gained, then what it lost"() {
        given:
        def before = StatusEntries.of([
            OID,
            '? old.txt',
            '? kept.txt'
        ].join('\u0000'))
        def after = StatusEntries.of([
            OID,
            '? kept.txt',
            '? new.txt'
        ].join('\u0000'))

        expect:
        StatusEntries.changedPaths(before, after) == ['new.txt', 'old.txt']
        StatusEntries.changedPaths(after, after) == []
    }

    // FR11: a path whose record changed from one shape to another is named once, not twice
    def "FR11: a path whose status line changed is named once"() {
        given:
        def before = StatusEntries.of('1 .M N... 100644 100644 100644 aaa aaa tracked.txt')
        def after = StatusEntries.of('1 M. N... 100644 100644 100644 aaa bbb tracked.txt')

        expect:
        StatusEntries.changedPaths(before, after) == ['tracked.txt']
    }

    // FR11: every record shape names its path; a path may hold spaces
    def "FR11: a #shape record names #path"() {
        expect:
        StatusEntries.pathOf(record) == path

        where:
        shape | record || path
        'ordinary' | '1 .M N... 100644 100644 100644 aaa aaa dir/my file.txt' || 'dir/my file.txt'
        'renamed' | '2 R. N... 100644 100644 100644 aaa aaa R100 moved name.txt' || 'moved name.txt'
        'unmerged' | 'u UU N... 100644 100644 100644 100644 aaa bbb ccc both.txt' || 'both.txt'
        'untracked' | '? new file.txt' || 'new file.txt'
        'HEAD header' | OID || OID.substring(2)
        'rename origin' | 'tracked.txt' || 'tracked.txt'
        'short ordinary' | '1 .M' || '1 .M'
    }
}
