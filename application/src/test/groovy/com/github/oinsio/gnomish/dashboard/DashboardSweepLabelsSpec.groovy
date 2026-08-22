package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import spock.lang.Specification

/**
 * {@link DashboardSweepLabels}, task 6.3 of add-serve-sandbox-lifecycle (UX1): the actions table's
 * verb reads as what happened to the object, and every category has one — a label helper must
 * never be the thing that fails a render.
 */
class DashboardSweepLabelsSpec extends Specification {

    def "every verdict category has a distinct action word"() {
        expect:
        DashboardSweepLabels.action(category) == label

        where:
        category | label
        SweepVerdictCategory.STOPPED_ORPHAN | 'stopped'
        SweepVerdictCategory.DISPOSED_AGED | 'disposed (aged)'
        SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE | 'disposed (reconstructible)'
        SweepVerdictCategory.CHECKED_ALIVE | 'checked alive'
        SweepVerdictCategory.KEPT_UNDER_THRESHOLD | 'kept'
        SweepVerdictCategory.SKIPPED_NO_VERDICT | 'skipped'
    }
}
