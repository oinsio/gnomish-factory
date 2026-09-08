package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * Designator properties of the {@link Tracker} port contract suite
 * (tracker-port spec, "Designators are classified once and derived per
 * adapter"): every adapter presents the SAME three shapes for the same
 * candidate data, whatever its own representation of that data is — GitHub
 * issue labels, an in-memory field, a Jira native field.
 *
 * <p>The seeding seam takes the candidate values as the tracker natively
 * carries them, not a finished shape, precisely so the classification is
 * exercised through the adapter rather than around it: an adapter that
 * resolved a conflict, applied a default, or invented a value would fail
 * these rows even though its own storage was seeded correctly.
 *
 * <p>The most-derived link in the chain, so a concrete adapter subclass
 * instantiates THIS class to run the full suite (M1).
 *
 * <p>Implements FR3 of add-base-ref-resolution.
 */
abstract class TrackerDesignatorContract extends TrackerShapeFactsContract {

    /** The first designator kind the factory consumes; every row here is about it. */
    protected static final String BASE_KIND = 'base'

    /**
     * The designator-seeding hook: makes {@code ref}'s task carry {@code values} as candidate
     * values for {@code kind}, in the adapter's OWN representation — the GitHub adapter writes one
     * label per value in the shape its configured rule matches, the in-memory reference records
     * the classified shape directly. Never seeds a finished shape from outside: the point of the
     * rows below is that the adapter's own extraction and the port's shared classification produce
     * it.
     *
     * @param adapter the tracker adapter arranged by {@link #arrange}
     * @param ref an already-seeded fixture task
     * @param kind the designator kind the values belong to
     * @param values the candidate values, in the order the tracker would report them
     */
    protected abstract void seedDesignatorCandidates(Tracker adapter, TaskRef ref, String kind, List<String> values)

    /** Seeds one Ready fixture task carrying {@code values} for kind {@code base}. */
    private Tracker withBaseCandidates(TaskRef ref, List<String> values) {
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'designator fixture')
        def adapter = tracker.get()
        seedTask(adapter, ref, new TrackerTaskState.Ready(), AbortFacts.none())
        if (!values.isEmpty()) {
            seedDesignatorCandidates(adapter, ref, BASE_KIND, values)
        }
        adapter
    }

    // FR3: data yielding no candidate is the absent shape -- never an empty string, never a default
    def "a task with no candidate yields the absent designator"() {
        given:
        def ref = new TaskRef('fixture:designator-absent')
        def adapter = withBaseCandidates(ref, [])

        expect:
        adapter.fetchTask(ref).designators().forKind(BASE_KIND) == new Designator.Absent()
    }

    // FR3: exactly one candidate yields the single shape, carrying the value verbatim
    def "a task with one candidate yields the single designator"() {
        given:
        def ref = new TaskRef('fixture:designator-single')
        def adapter = withBaseCandidates(ref, ['release/1.18'])

        expect:
        adapter.fetchTask(ref).designators().forKind(BASE_KIND) == new Designator.Single('release/1.18')
    }

    // FR3: two different candidates are a conflict listing both, in the order the adapter reports
    //     them -- no adapter picks a winner
    def "a task with two differing candidates yields a conflict listing both"() {
        given:
        def ref = new TaskRef('fixture:designator-conflict')
        def adapter = withBaseCandidates(ref, [
            'release/1.18',
            'release/1.19'
        ])

        expect:
        adapter.fetchTask(ref).designators().forKind(BASE_KIND) ==
                new Designator.Conflict([
                    'release/1.18',
                    'release/1.19'
                ])
    }

    // FR3: the same value twice is one selection, not a conflict -- equal duplicates collapse
    def "a task carrying the same value twice yields the single designator"() {
        given:
        def ref = new TaskRef('fixture:designator-duplicates')
        def adapter = withBaseCandidates(ref, [
            'release/1.18',
            'release/1.18'
        ])

        expect:
        adapter.fetchTask(ref).designators().forKind(BASE_KIND) == new Designator.Single('release/1.18')
    }

    // FR3: kinds are open -- a kind this adapter extracts nothing for is absent, not an error
    def "a kind the adapter does not extract reads as absent"() {
        given:
        def ref = new TaskRef('fixture:designator-unknown-kind')
        def adapter = withBaseCandidates(ref, ['release/1.18'])

        expect:
        adapter.fetchTask(ref).designators().forKind('no-such-kind') == new Designator.Absent()
    }
}
