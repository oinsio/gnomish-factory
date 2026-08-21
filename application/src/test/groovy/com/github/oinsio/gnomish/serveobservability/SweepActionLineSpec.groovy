package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * {@link SweepActionLine}, task 6.2 of add-serve-sandbox-lifecycle (NFR-O2): "untouched objects
 * are never itemized in the ledger" is enforced by the type, so a caller cannot flood a day's
 * ledger with one line per container, and every field a reader needs is non-blank by construction.
 */
class SweepActionLineSpec extends Specification {

    static final InstanceInfo INSTANCE = new InstanceInfo('gnome-1', 'host1', '1.0.0')
    static final Instant AT = Instant.parse('2026-08-06T09:00:00Z')

    private static SweepActionLine line(SweepVerdictCategory category) {
        new SweepActionLine(
                INSTANCE, AT, 'gnomish-task-1-box', 'main-box', 'tracked', 'task-1',
                category, 'unowned running main-box', Duration.ofMinutes(15))
    }

    // NFR-O2: only the three acting categories become their own line.
    def "an acting category is accepted"() {
        expect:
        line(category).category() == category

        where:
        category << [
            SweepVerdictCategory.STOPPED_ORPHAN,
            SweepVerdictCategory.DISPOSED_AGED,
            SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE
        ]
    }

    // NFR-O2: an untouched object itemized as an action is a programming error, not a line.
    def "an untouched category is rejected"() {
        when:
        line(category)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "SweepActionLine.category must be a stop or dispose, was ${category}"

        where:
        category << [
            SweepVerdictCategory.CHECKED_ALIVE,
            SweepVerdictCategory.KEPT_UNDER_THRESHOLD,
            SweepVerdictCategory.SKIPPED_NO_VERDICT
        ]
    }

    def "a blank required field is rejected, naming the component"() {
        when:
        new SweepActionLine(
                INSTANCE, AT, objectName, role, mode, taskKey,
                SweepVerdictCategory.STOPPED_ORPHAN, reason, null)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "SweepActionLine.${component} must not be blank"

        where:
        objectName | role | mode | taskKey | reason | component
        ' ' | 'main-box' | 'tracked' | 'task-1' | 'reason' | 'objectName'
        'box' | ' ' | 'tracked' | 'task-1' | 'reason' | 'role'
        'box' | 'main-box' | ' ' | 'task-1' | 'reason' | 'mode'
        'box' | 'main-box' | 'tracked' | ' ' | 'reason' | 'taskKey'
        'box' | 'main-box' | 'tracked' | 'task-1' | ' ' | 'reason'
    }

    // NFR-O2: an age is optional — a stop verdict measures none.
    def "an absent age is legal"() {
        expect:
        new SweepActionLine(
                INSTANCE, AT, 'box', 'main-box', 'tracked', 'task-1',
                SweepVerdictCategory.STOPPED_ORPHAN, 'reason', null).age() == null
    }
}
