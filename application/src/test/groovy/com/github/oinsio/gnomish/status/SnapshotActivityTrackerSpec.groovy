package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Instant
import spock.lang.Specification

/**
 * SnapshotActivityTracker (task 6.3 of add-manual-run): the {@code
 * ActivityTracker} bridge {@code DialogConsole} marks/restores around each
 * blocking prompt, since the engine itself has no notion of a human being
 * prompted. {@code markAwaitingInput} stamps an {@link Activity.AwaitingInput}
 * onto the held snapshot and hands back whatever activity was current before
 * the prompt, so {@code restore} can put it back once the read returns.
 *
 * <p>Implements FR10, D7 of add-manual-run.
 */
class SnapshotActivityTrackerSpec extends Specification {

    private static final Instant NOW = Instant.parse('2026-07-17T09:00:00Z')

    def "markAwaitingInput stamps an AwaitingInput activity onto the held snapshot with the clock's instant"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        def clock = new VirtualClock(NOW)
        def tracker = new SnapshotActivityTracker(holder, clock)

        when:
        tracker.markAwaitingInput('pass/fail? ')

        then:
        def activity = holder.activity().activity()
        activity instanceof Activity.AwaitingInput
        activity.prompt() == 'pass/fail? '
        activity.since() == NOW
    }

    def "markAwaitingInput returns whatever activity was current before the prompt"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        def previous = new Activity.Executing(NOW)
        holder.updateActivity(previous)
        def tracker = new SnapshotActivityTracker(holder, new VirtualClock(NOW))

        when:
        def returned = tracker.markAwaitingInput('pass/fail? ')

        then:
        returned.is(previous)
    }

    def "markAwaitingInput returns null when no activity was current before the prompt"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        def tracker = new SnapshotActivityTracker(holder, new VirtualClock(NOW))

        when:
        def returned = tracker.markAwaitingInput('pass/fail? ')

        then:
        returned == null
    }

    def "restore puts the previously-current activity back onto the held snapshot"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        def previous = new Activity.Executing(NOW)
        def tracker = new SnapshotActivityTracker(holder, new VirtualClock(NOW))

        when:
        tracker.restore(previous)

        then:
        holder.activity().activity().is(previous)
    }

    def "restore with null activity puts the snapshot back to idle"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        holder.updateActivity(new Activity.Executing(NOW))
        def tracker = new SnapshotActivityTracker(holder, new VirtualClock(NOW))

        when:
        tracker.restore(null)

        then:
        holder.activity().activity() == null
    }
}
