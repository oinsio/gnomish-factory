package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.adapter.agent.AgentProgressEvent
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.time.Instant
import spock.lang.Specification

/**
 * AgentActivityEnricher (task 8.2 of add-agent-executor): the {@code
 * AgentProgressListener} that enriches the held {@link Activity.Executing} with
 * live tool detail as an executor round's CLI process reports {@code ToolStarted}
 * / {@code RoundFinished} progress — a judge round runs under {@link
 * Activity.Verifying}, never {@code Executing}, so this listener's "only mutate on
 * {@code Executing}" rule naturally excludes judge rounds without any special
 * dispatch (executor rounds only).
 *
 * <p>Implements FR7, UX1, D10, D12 of add-agent-executor.
 */
class AgentActivityEnricherSpec extends Specification {

    private static final Instant SINCE = Instant.parse('2026-07-17T09:00:00Z')

    def "RoundStarted is a no-op: AttemptStarted already set Executing before the round begins"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        holder.updateActivity(new Activity.Executing(SINCE))
        def enricher = new AgentActivityEnricher(holder)

        when:
        enricher.onProgress(new AgentProgressEvent.RoundStarted('claude-x', 'session-1'))

        then:
        holder.activity().activity() == new Activity.Executing(SINCE)
    }

    def "ToolStarted sets currentTool and increments toolCalls, preserving since"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        holder.updateActivity(new Activity.Executing(SINCE))
        def enricher = new AgentActivityEnricher(holder)

        when:
        enricher.onProgress(new AgentProgressEvent.ToolStarted('run_tests'))

        then:
        def activity = holder.activity().activity()
        activity.since() == SINCE
        activity.currentTool() == 'run_tests'
        activity.toolCalls() == 1
    }

    def "consecutive ToolStarted events accumulate the tool count and update the current tool"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        holder.updateActivity(new Activity.Executing(SINCE))
        def enricher = new AgentActivityEnricher(holder)

        when:
        enricher.onProgress(new AgentProgressEvent.ToolStarted('run_tests'))
        enricher.onProgress(new AgentProgressEvent.ToolStarted('edit_file'))

        then:
        def activity = holder.activity().activity()
        activity.since() == SINCE
        activity.currentTool() == 'edit_file'
        activity.toolCalls() == 2
    }

    def "ToolStarted is a no-op when current activity is not Executing (judge round under Verifying)"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        def verifying = new Activity.Verifying(new CheckRef(0, 'judge:acceptance'), SINCE)
        holder.updateActivity(verifying)
        def enricher = new AgentActivityEnricher(holder)

        when:
        enricher.onProgress(new AgentProgressEvent.ToolStarted('run_tests'))

        then:
        holder.activity().activity().is(verifying)
    }

    def "ToolStarted is a no-op when there is no current activity"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        def enricher = new AgentActivityEnricher(holder)

        when:
        enricher.onProgress(new AgentProgressEvent.ToolStarted('run_tests'))

        then:
        holder.activity().activity() == null
    }

    def "RoundFinished clears currentTool and resets toolCalls, preserving since"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        holder.updateActivity(new Activity.Executing(SINCE, 'run_tests', 3))
        def enricher = new AgentActivityEnricher(holder)

        when:
        enricher.onProgress(new AgentProgressEvent.RoundFinished('done'))

        then:
        holder.activity().activity() == new Activity.Executing(SINCE)
    }

    def "RoundFinished is a no-op when current activity is not Executing"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
        def verifying = new Activity.Verifying(new CheckRef(0, 'judge:acceptance'), SINCE)
        holder.updateActivity(verifying)
        def enricher = new AgentActivityEnricher(holder)

        when:
        enricher.onProgress(new AgentProgressEvent.RoundFinished('done'))

        then:
        holder.activity().activity().is(verifying)
    }
}
