package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import spock.lang.Specification

/**
 * {@code fetchTask}'s designators are the port's shared classification of the candidates the
 * configured rule extracts — nothing the adapter decides itself: the real {@link GithubTracker} on the
 * stateful fixture GitHub of {@link GithubKillWindowWorld}, its rules compiled from a {@code
 * tracker.github} subsection.
 *
 * <p>Implements FR3 of add-base-ref-resolution.
 */
class GithubDesignatorClassificationSpec extends Specification {

    private static final GithubDesignatorRules RULES = GithubDesignatorRules.from([designators: [base: 'base:(.+)']])

    private final GithubKillWindowWorld world = new GithubKillWindowWorld()

    def cleanup() {
        world.close()
    }

    // FR3: no resolution, defaulting or conflict-picking in the adapter — the port function decides.
    // Scenario: tracker/github-tracker / Candidates are classified only by the shared port function
    def "fetchTask reports exactly the port's classification of the candidates #candidates (#shape)"() {
        given: 'a ready issue wearing the labels the rule matches, and one it does not'
        world.open({ FixtureSeeder s, FixtureIssue i ->
            s.seedTask(i, new TrackerTaskState.Ready(), AbortFacts.none())
            i.addLabel('my-base:ignored')
            candidates.each { i.addLabel("base:${it}".toString()) }
        }, RULES)

        when:
        def designator = world.tracker().fetchTask(world.ref()).designators().forKind('base')

        then:
        designator == Designator.classify(candidates)
        designator.class.simpleName == shape

        where:
        candidates | shape
        [] | 'Absent'
        ['release/1.18'] | 'Single'
        [
            'release/1.18',
            'release/1.19'
        ] | 'Conflict'
        [
            'release/1.19',
            'release/1.18'
        ] | 'Conflict'
    }
}
