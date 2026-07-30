package com.github.oinsio.gnomish.app.port.tracker

import java.time.Instant
import spock.lang.Specification

/**
 * OpenTask: one entry of listOpen — a task's ref, its logical state, and, for a
 * Working task with a live claim, the claim version; the holder is read from the
 * state, never duplicated (design D5). Implements FR5 of add-claim-heartbeat.
 */
class OpenTaskSpec extends Specification {

    private static final TaskRef REF = new TaskRef('github:owner/repo#42')

    // FR5: a Working entry carries its state and claim version; the holder is derived from the state
    def "exposes ref, state and claim version for a working task"() {
        given:
        def state = new TrackerTaskState.Working('gnomish-factory-x7k2q1')
        def version = new ClaimVersion('claim-comment-991', Instant.parse('2026-07-29T10:15:30Z'))

        when:
        def entry = new OpenTask(REF, state, version)

        then:
        entry.ref() == REF
        entry.state() == state
        entry.claimVersion() == version

        and: 'the holder is not duplicated — it is read back from the Working state'
        ((TrackerTaskState.Working) entry.state()).holder() == 'gnomish-factory-x7k2q1'
    }

    // FR5: an AwaitingHuman entry has no claim version — it carries no live claim
    def "carries a null claim version for an AwaitingHuman task"() {
        when:
        def entry = new OpenTask(REF, new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), null)

        then:
        entry.state() == new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)
        entry.claimVersion() == null
    }

    // FR5, D2: a Working task whose claim marker is missing has an absent (null) version
    def "allows a null claim version for a Working task with a missing claim marker"() {
        when:
        def entry = new OpenTask(REF, new TrackerTaskState.Working('gnomish-factory-x7k2q1'), null)

        then:
        entry.claimVersion() == null
    }

    // FR5: open tasks are values — equal content means equal entries
    def "entries with the same components are equal values"() {
        given:
        def state = new TrackerTaskState.Working('a')
        def version = new ClaimVersion('m1', Instant.parse('2026-07-29T10:15:30Z'))

        expect:
        new OpenTask(REF, state, version) == new OpenTask(REF, state, version)

        and: 'a differing version makes them unequal'
        new OpenTask(REF, state, version) !=
                new OpenTask(REF, state, new ClaimVersion('m2', Instant.parse('2026-07-29T10:15:30Z')))

        and: 'a present versus absent version makes them unequal'
        new OpenTask(REF, state, version) != new OpenTask(REF, state, null)
    }
}
