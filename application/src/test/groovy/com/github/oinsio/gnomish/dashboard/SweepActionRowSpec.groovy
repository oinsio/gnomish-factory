package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import java.time.Instant
import spock.lang.Specification

/**
 * {@link SweepActionRow}, task 6.4 of add-serve-sandbox-lifecycle (UX2): a stopped orphan reads as
 * a dead-or-hung instance ONLY in {@code tracked} mode — a manual age-policy stop is routine, and
 * conflating the two would make the incident alert cry wolf on every debugging session.
 */
class SweepActionRowSpec extends Specification {

    private static SweepActionRow row(SweepVerdictCategory category, String mode) {
        new SweepActionRow(
                Instant.parse('2026-08-06T09:00:00Z'), 'box', 'main-box', mode, 'task-1', category, 'reason', null)
    }

    def "only a tracked stopped-orphan is a dead-instance symptom"() {
        expect:
        row(category, mode).isDeadInstanceSymptom() == symptom

        where:
        category | mode | symptom
        SweepVerdictCategory.STOPPED_ORPHAN | 'tracked' | true
        SweepVerdictCategory.STOPPED_ORPHAN | 'manual' | false
        SweepVerdictCategory.DISPOSED_AGED | 'tracked' | false
        SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE | 'tracked' | false
    }
}
