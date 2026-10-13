package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.CarrierSinkScan
import spock.lang.Specification

/**
 * FR1, FR2 of harden-untrusted-text-sinks; FR7 of type-untrusted-text: the two local-string shapes
 * of the bypass, run through the type gate's own sink detector — {@link CarrierSinkScan}, the
 * rule (c) scan {@link UntrustedTextSinkGateSpec} applies to every production source. A carrier
 * that reached a log call through a local, and text the findings sanitizer prepared for a log
 * call, must each be named at their line.
 */
class LocalStringBypassGateSpec extends Specification {

    /** Rule (c)'s detector, as the gate runs it on one comment-stripped source. */
    private static List<String> namedSites(String source) {
        CarrierSinkScan.violations(source, 'Seeded.java', [] as Set)
    }

    // F98: text the findings sanitizer prepared reached a log call unnamed; the log-call scan now
    // names the findings exit at a log site, as TrackerPublicationOwnerSpec's WRONG_EXIT does for writes
    // Scenario: observability/factory-logging / The local-string bypass fails the gate
    def "FR2: a log call fed a carrier held in a local, or text the findings sanitizer prepared, is named by the type gate"() {
        expect: 'a carrier held in a local variable and passed to the logger without an exit'
        namedSites('UntrustedText stderr = result.stderr();\nlog.warn("push failed: {}", stderr);') == ['Seeded.java:2']

        and: 'the findings funnel\'s sanitizer used to prepare a log line outside the funnel'
        namedSites('String text = describe();\nlog.warn("check failed: {}", FindingsSanitizer.forLog(text));') ==
                ['Seeded.java:2']
    }
}
