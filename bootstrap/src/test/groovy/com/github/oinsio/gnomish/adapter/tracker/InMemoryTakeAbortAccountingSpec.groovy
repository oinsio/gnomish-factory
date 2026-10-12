package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.AbortLifecycleFixture
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * `tracker-take` abort accounting across take invocations, each a freshly built factory instance
 * ({@link AbortLifecycleFixture#newCommand}) over one shared {@link InMemoryTracker}: the count the
 * fuse reads is the tracker's, the mandate neither consults nor resets it, and the branch keeps
 * what the tracker write had to cap. Aborts are forced by {@link AccountingTracker} at the round
 * boundary's "still ours" read, the technique of {@code InMemoryTakeLifecycleAbortSpec}.
 *
 * <p>FR9, FR10, FR14 of add-tracker-port; FR14 of harden-task-branch-contract; FR1 of
 * cap-abort-cause-length.
 */
class InMemoryTakeAbortAccountingSpec extends Specification implements AbortLifecycleFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final Instant START = Instant.parse('2026-01-01T00:00:00Z')

    @TempDir
    Path tempDir

    private InMemoryTracker realTracker
    private AccountingTracker accounting

    def setup() {
        realTracker = new InMemoryTracker()
        new InMemoryTrackerHarness(realTracker).seed(REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('Add widgets'),
                UntrustedText.tracker('please add widgets')), new TrackerTaskState.Ready(), AbortFacts.none())
        accounting = new AccountingTracker(realTracker)
        tracker = accounting
        trackerFactory = new FixedTrackerAdapterFactory({ accounting })
        writeAbortProjectFixture()
    }

    private int take(Instant now) {
        try {
            newCommand(now).run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))
            throw new IllegalStateException('take returned without an exit code')
        } catch (com.github.oinsio.gnomish.app.TakeExitCodeException e) {
            e.exitCode()
        }
    }

    private List<String> thread() {
        new InMemoryTrackerHarness(realTracker).threadAsStrings(REF)
    }

    // FR14: the second instance reads the first one's abort from the tracker, and the fuse trips on it.
    // Scenario: tracker/tracker-take / Aborts on different instances share one count
    def "an abort on one instance and an abort on another trip the fuse on one shared count"() {
        given: 'instance A aborts the task below the fuse (K=2)'
        accounting.armAbort('simulated tracker outage at the round boundary')
        assert take(START) == 12
        assert realTracker.fetchTask(REF).abortFacts().crashCount() == 1

        when: 'instance B claims it and aborts too, its own progress marker lost so nothing resets the count'
        accounting.armAbort('simulated tracker outage at the round boundary', true)
        def exitB = take(START.plusSeconds(1))

        then: 'B read A\'s abort as a crash-cause attempt when it claimed'
        accounting.factsAtClaim.last().crashCount() == 1

        and: 'the fuse decision rests on that one count: A\'s abort plus B\'s reach K, and the task parks INFRA'
        exitB == 13
        thread().find {
            it.startsWith('PARK:')
        }.toLowerCase().contains('threshold')

        and: 'two instances wrote that history'
        def claims = thread().findAll { it.startsWith('CLAIM:') }
        claims.size() == 2
        claims[0] != claims[1]
    }

    // FR9: the mandate claims through the backoff (and, on GitHub, without the ready label: an open
    // issue fetches as Ready whatever its labels, `GithubTaskFetcherSpec`), and leaves the count alone.
    // Scenario: tracker/tracker-take / Mandate overrides readiness and backoff
    def "take <ref> claims and works a task inside its abort backoff without resetting the abort count"() {
        given: 'one abort a second ago: a 2-minute backoff hides the task from the bare feed'
        accounting.armAbort('simulated tracker outage at the round boundary')
        assert take(START) == 12

        when: 'the operator takes the ref explicitly, inside the backoff window'
        def exit = take(START.plusSeconds(1))

        then: 'the task was claimed and worked to delivery'
        exit == 0
        realTracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'right after the claim, the abort count was still the one abort on record'
        accounting.factsAtClaim.last().count() == 1
    }

    // FR1 of cap-abort-cause-length: the cap is the tracker's bound, not the record's.
    // Scenario: tracker/tracker-take / The branch keeps the uncapped cause
    def "an over-budget cause is capped on the tracker and kept whole on the task branch"() {
        given: 'a cause far past the abort-cause budget, ending in a marker only an uncapped copy keeps whole'
        def cause = 'outage-head ' + ('x' * 40_000) + ' outage-tail-marker'

        when:
        accounting.armAbort(cause)
        def exit = take(START)

        then:
        exit == 12

        and: 'the abort marker the tracker was handed carries the capped cause, the omission named'
        def written = accounting.abortRecords.last().cause()
        written.contains('characters omitted')
        !written.contains(cause)

        and: 'the branch records the full cause'
        def taskJson = gitOutput(projectDir, 'show', 'gnomish/PROJ-1:.gnomish-task/task.json')
        def outcome = TaskJsonMapper.readDto(UntrustedText.branchDocument(taskJson)).outcome() as TaskOutcomeDto.Aborted
        outcome.cause().contains(cause)
    }
}

/**
 * Test-only decorator for {@link InMemoryTakeAbortAccountingSpec}: once armed, the SECOND
 * {@code fetchTask} of the next run — the round-boundary check — throws with the armed message,
 * optionally failing that run's progress marker too (the {@code ThrowOnNextFetchTracker} shapes,
 * with a chosen message); and every claim records the abort facts the tracker held right after it.
 */
class AccountingTracker extends DelegatingTracker {

    final List<AbortFacts> factsAtClaim = []
    final List<AbortRecord> abortRecords = []
    private String armedMessage
    private int fetchesSinceArmed
    private boolean failNextProgress

    AccountingTracker(InMemoryTracker delegate) {
        super(delegate)
    }

    void armAbort(String message, boolean alsoFailProgress = false) {
        armedMessage = message
        fetchesSinceArmed = 0
        failNextProgress = alsoFailProgress
    }

    @Override
    com.github.oinsio.gnomish.app.port.tracker.TrackerTask fetchTask(TaskRef ref) {
        if (armedMessage != null && ++fetchesSinceArmed == 2) {
            String message = armedMessage
            armedMessage = null
            throw new RuntimeException(message)
        }
        delegate.fetchTask(ref)
    }

    @Override
    com.github.oinsio.gnomish.app.port.tracker.ClaimResult claim(TaskRef ref, String instanceId) {
        def result = delegate.claim(ref, instanceId)
        factsAtClaim << delegate.fetchTask(ref).abortFacts()
        result
    }

    @Override
    void recordAbort(TaskRef ref, AbortRecord record) {
        abortRecords << record
        delegate.recordAbort(ref, record)
    }

    @Override
    void recordProgress(TaskRef ref) {
        if (failNextProgress) {
            failNextProgress = false
            throw new RuntimeException('simulated tracker outage recording progress')
        }
        delegate.recordProgress(ref)
    }
}
