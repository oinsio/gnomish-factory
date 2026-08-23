package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import java.time.Instant
import spock.lang.Specification

/**
 * {@link SandboxHygieneView}, task 6.3 of add-serve-sandbox-lifecycle (NFR-O3): the two halves —
 * snapshot vital and ledger actions — are independently present: the hygiene block reads the
 * vital, {@link SandboxHygieneAlertEvaluator} reads the actions, and each degrades alone.
 */
class SandboxHygieneViewSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')

    private static SweepActionRow row() {
        new SweepActionRow(
                NOW, 'box', 'main-box', 'tracked', 'task-1', SweepVerdictCategory.STOPPED_ORPHAN, 'reason', null)
    }

    def "absent carries neither half"() {
        given:
        def view = SandboxHygieneView.absent()

        expect:
        view.sweep() == null
        view.recentActions().isEmpty()
    }

    def "the actions list is copied defensively"() {
        given:
        def mutable = [row()]
        def view = new SandboxHygieneView(null, mutable)

        when:
        mutable.clear()

        then:
        view.recentActions().size() == 1
    }
}
