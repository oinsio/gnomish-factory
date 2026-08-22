package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * design: Migration of add-serve-sandbox-lifecycle — objects lacking the mode label (created by a
 * pre-upgrade build) or carrying a value this build does not recognize classify as {@code
 * tracked}, the more-protected mode, never as the less-protected {@code manual} mode. The
 * mixed-version host degrades safely: nothing is insta-reaped just because it predates this label.
 */
class ObjectOwnershipClassifierSpec extends Specification {

    def "a labelled tracked object classifies as tracked"() {
        expect:
        ObjectOwnershipClassifier.classify(['com.github.oinsio.gnomish.mode': 'tracked']) == OwnershipMode.TRACKED
    }

    def "a labelled manual object classifies as manual"() {
        expect:
        ObjectOwnershipClassifier.classify(['com.github.oinsio.gnomish.mode': 'manual']) == OwnershipMode.MANUAL
    }

    def "an object with no mode label at all (a pre-upgrade build) classifies as tracked"() {
        expect:
        ObjectOwnershipClassifier.classify([:]) == OwnershipMode.TRACKED
        ObjectOwnershipClassifier.classify(['com.github.oinsio.gnomish.factory': 'true']) == OwnershipMode.TRACKED
    }

    def "an unrecognized mode value classifies as tracked, the fail-safe fallback"() {
        expect:
        ObjectOwnershipClassifier.classify(['com.github.oinsio.gnomish.mode': 'quarantined']) == OwnershipMode.TRACKED
    }
}
