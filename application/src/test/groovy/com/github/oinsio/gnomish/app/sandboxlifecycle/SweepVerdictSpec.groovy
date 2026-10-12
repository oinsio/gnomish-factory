package com.github.oinsio.gnomish.app.sandboxlifecycle

import spock.lang.Specification

/**
 * {@link SweepVerdict}'s one invariant (D17, F90 of tighten-spec-contract): a skipped verdict
 * names where it failed, and a reached verdict names no failure — so the stall alarm always has a
 * source to name and never names one that did not fail.
 *
 * NFR-O3 of add-serve-sandbox-lifecycle.
 */
class SweepVerdictSpec extends Specification {

    private static SweepVerdict verdict(SweepVerdictCategory category, SkippedVerdictSource source) {
        new SweepVerdict(category, 'obj', 'main-box', 'tracked', 'task-1', 'reason', null, source)
    }

    def "a skipped verdict carries its source and a reached verdict carries none"() {
        expect:
        verdict(category, source).skipSource() == source

        where:
        category | source
        SweepVerdictCategory.SKIPPED_NO_VERDICT | SkippedVerdictSource.TRACKER
        SweepVerdictCategory.SKIPPED_NO_VERDICT | SkippedVerdictSource.RUNTIME
        SweepVerdictCategory.CHECKED_ALIVE | null
        SweepVerdictCategory.DISPOSED_AGED | null
    }

    def "a skipped verdict without a source, or a reached verdict with one, is refused"() {
        when:
        verdict(category, source)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'SweepVerdict.skipSource must be present exactly when the category is SKIPPED_NO_VERDICT, got ' +
                category + ' with ' + source

        where:
        category | source
        SweepVerdictCategory.SKIPPED_NO_VERDICT | null
        SweepVerdictCategory.STOPPED_ORPHAN | SkippedVerdictSource.RUNTIME
    }
}
