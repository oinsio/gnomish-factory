package com.github.oinsio.gnomish.board

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.take.BackoffPolicy
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * BoardModel's Ready-row eligibility annotation (task 2.2, design D7):
 * mirrors FeedPolicy.selectClaimCandidates's skip-reason precedence — in
 * backoff, then finished, then WIP-held — without reimplementing it.
 * Implements FR2 of add-board-command.
 */
class BoardModelEligibilitySpec extends Specification {

    private static final Instant NOW = Instant.parse('2026-08-05T09:00:00Z')
    private static final Duration BASE = BackoffPolicy.DEFAULT_BASE
    private static final Duration CAP = BackoffPolicy.DEFAULT_CAP

    // count=1 abort one minute ago: still inside the 2m base delay, so this task is backed off
    private static final AbortFacts BACKED_OFF_FACTS = new AbortFacts(1, NOW - Duration.ofMinutes(1))
    private static final Instant BACKED_OFF_DEADLINE = BACKED_OFF_FACTS.lastAbortAt() + BackoffPolicy.delay(1, BASE, CAP)

    private static ReadyTask task(AbortFacts abortFacts, boolean returned, boolean finished) {
        new ReadyTask(new TaskRef('github:o/r#1'), abortFacts, returned, finished, 'title')
    }

    // FR2, D7: eligibility precedence — backoff, then finished, then WIP-held — over every reason combination
    // Suppressed: IntelliJ's Groovy static checker misinfers this where-block's column types
    // (reports abortFacts/returned/finished as String/Boolean/Boolean); Spock resolves them
    // correctly at runtime from the table — this is an IDE-only false positive.
    @SuppressWarnings('GroovyAssignabilityCheck')
    def "annotates the Ready row with the feed's own precedence reason"() {
        given: 'a single ready task built from the scenario\'s facts'
        def readyTasks = [
            task(abortFacts, returned, finished)
        ]

        when: 'the model is built with the scenario\'s WIP parameters'
        def model = BoardModel.build(readyTasks, [], false, NOW, BASE, CAP, NOW, openFrontCount, wipLimit)

        then: 'the row carries exactly the expected reason'
        model.readyRows()[0].eligibilityReason() == expectedReason

        where:
        description | abortFacts | returned | finished | openFrontCount | wipLimit || expectedReason
        'eligible: fresh, no backoff, front below cap' | AbortFacts.none() | false | false | 0 | 3 || null
        'eligible: returned, no backoff, front below cap' | AbortFacts.none() | true | false | 0 | 3 || null
        'in backoff' | BACKED_OFF_FACTS | false | false | 0 | 3 || new EligibilityReason.InBackoff(BACKED_OFF_DEADLINE)
        'finished, not backed off' | AbortFacts.none() | false | true | 0 | 3 || new EligibilityReason.Finished()
        'WIP-held: fresh, front at limit' | AbortFacts.none() | false | false | 3 | 3 || new EligibilityReason.WipHeld()
        'WIP-held: fresh, front above limit' | AbortFacts.none() | false | false | 4 | 3 || new EligibilityReason.WipHeld()
        'returned bypasses the WIP gate even when front is full' | AbortFacts.none() | true | false | 3 | 3 || null
        'backoff wins over finished (both apply)' | BACKED_OFF_FACTS | false | true | 0 | 3 || new EligibilityReason.InBackoff(BACKED_OFF_DEADLINE)
        'finished wins over WIP-held (both apply, fresh)' | AbortFacts.none() | false | true | 3 | 3 || new EligibilityReason.Finished()
        'backoff wins over WIP-held too (all three apply)' | BACKED_OFF_FACTS | false | true | 3 | 3 || new EligibilityReason.InBackoff(BACKED_OFF_DEADLINE)
    }

    // Task 2.2 seam confirmation: the shorter build overload still defaults every row to eligible
    def "the four-argument build overload keeps defaulting Ready rows to eligible"() {
        given: 'a fresh ready task with no abort history'
        def readyTasks = [
            task(AbortFacts.none(), false, false)
        ]

        when: 'the model is built via the shorter overload'
        def model = BoardModel.build(readyTasks, [], false, NOW)

        then: 'the row carries no eligibility reason'
        model.readyRows()[0].eligibilityReason() == null
    }
}
