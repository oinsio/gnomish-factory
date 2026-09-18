package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * TaskSnapshot: the task's id/title/body frozen at first claim, unaffected by
 * later tracker edits (FR11). Implements FR11 of add-tracker-port.
 */
class TaskSnapshotSpec extends Specification {

    // FR11: all three components round-trip exactly as constructed
    def "exposes id, title and body exactly as constructed"() {
        when:
        def snapshot = new TaskSnapshot('github:owner/repo#42', UntrustedText.tracker('Fix the thing'), UntrustedText.tracker('Please fix it.'))

        then:
        snapshot.id() == 'github:owner/repo#42'
        snapshot.title().forLog() == 'Fix the thing'
        snapshot.body().forLog() == 'Please fix it.'
    }

    // FR11: many tracker issues have no description — an empty body is legal
    def "an empty body is accepted"() {
        expect:
        new TaskSnapshot(
                'github:owner/repo#42',
                UntrustedText.tracker('Fix the thing'),
                UntrustedText.tracker('')).body().forLog() == ''
    }

    // FR11: a task with no identity or no title is not a meaningful snapshot
    def "blank id or title is rejected with the component named"() {
        when:
        new TaskSnapshot(id, UntrustedText.tracker(title), UntrustedText.tracker('body'))

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains(expectedComponent)

        where:
        id | title || expectedComponent
        '' | 'Fix the thing' || 'TaskSnapshot.id'
        '   ' | 'Fix the thing' || 'TaskSnapshot.id'
        'github:owner/repo#42' | '' || 'TaskSnapshot.title'
        'github:owner/repo#42' | '  \t' || 'TaskSnapshot.title'
    }

    // FR11: snapshots are values — equal content means equal snapshots
    def "snapshots with the same components are equal values"() {
        expect:
        new TaskSnapshot('id', UntrustedText.tracker('title'), UntrustedText.tracker('body')) == new TaskSnapshot('id', UntrustedText.tracker('title'), UntrustedText.tracker('body'))

        and: 'a differing body makes them unequal'
        new TaskSnapshot('id', UntrustedText.tracker('title'), UntrustedText.tracker('a')) != new TaskSnapshot('id', UntrustedText.tracker('title'), UntrustedText.tracker('b'))
    }
}
