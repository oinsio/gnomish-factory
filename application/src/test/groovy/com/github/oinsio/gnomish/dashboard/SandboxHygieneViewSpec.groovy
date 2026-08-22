package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.SweepVital
import java.time.Instant
import spock.lang.Specification

/**
 * {@link SandboxHygieneView}, task 6.3 of add-serve-sandbox-lifecycle (NFR-O3): the two halves —
 * snapshot vital and ledger actions — are independently present, so "no sweep data yet" means
 * neither, not either.
 */
class SandboxHygieneViewSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')

    private static SweepVital vital() {
        new SweepVital(NOW, 300L, SweepCounts.NONE, [], 0, 0)
    }

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
        view.actionsTotal() == 0
        view.isEmpty()
    }

    // NFR-O3: either half alone is still data worth rendering.
    def "isEmpty is true only when neither half has data"() {
        expect:
        new SandboxHygieneView(sweep, actions, actions.size()).isEmpty() == empty

        where:
        sweep | actions | empty
        null | [] | true
        vital() | [] | false
        null | [row()] | false
        vital() | [row()] | false
    }

    def "actionsTruncated is true exactly when the window held more than the table carries"() {
        expect:
        new SandboxHygieneView(null, actions, total).actionsTruncated() == truncated

        where:
        actions | total | truncated
        [] | 0 | false
        [row()] | 1 | false
        [row()] | 4 | true
    }

    def "the actions list is copied defensively"() {
        given:
        def mutable = [row()]
        def view = new SandboxHygieneView(null, mutable, 1)

        when:
        mutable.clear()

        then:
        view.recentActions().size() == 1
    }
}
