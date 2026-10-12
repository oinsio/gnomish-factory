package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant

/**
 * The fixture both {@link TakeBareAutoSpec} (the walk over the queue) and {@link
 * TakeBareAutoFeedOrderSpec} (the order the walk visits) drive {@link TakeBareAuto#run} through:
 * a real clone with an {@code origin} remote ({@link TakeResumeSpecBase}), a mocked tracker, and a
 * fixed head-zone pick.
 */
abstract class TakeBareAutoSpecBase extends TakeResumeSpecBase {

    protected static final Duration BASE = Duration.ofMinutes(2)
    protected static final Duration CAP = Duration.ofHours(1)
    protected static final Instant NOW = Instant.parse('2026-07-23T12:00:00Z')

    /** Never blocks on the WIP limit — the default for scenarios that carry no WIP concern. */
    protected static final int UNLIMITED_WIP = Integer.MAX_VALUE

    /** Fixes {@link com.github.oinsio.gnomish.app.take.FeedPolicy}'s head-zone pick to a chosen
     *  index; the default (0) preserves strict oldest-first order for scenarios that don't
     *  exercise the shuffle. */
    protected static Random headPick(int index = 0) {
        new Random() {
                    @Override
                    int nextInt(int bound) {
                        return index
                    }
                }
    }

    protected TakeBareAuto newBareAuto(int wipLimit = UNLIMITED_WIP, Random random = headPick()) {
        // FR5, FR13 of add-base-ref-resolution: the shorter constructor's placeholder trusted tier
        // ('HEAD', a non-existent origin ref) is only safe for a scenario that never reaches a
        // fresh claim's base resolution; setup() wires a real 'origin' remote (TakeResumeSpecBase),
        // so this spec's claimed scenarios need the clone's actual default branch instead — which
        // is the trusted tier the fixture's slot wiring carries.
        new TakeBareAuto(slotWiring(newAssembly(FakeAgentSupport.propertiesFor('plain-round'), TakeCommands.slotTime(NOW)), TaskGitFixture.real()), BASE, CAP, wipLimit, random)
    }

    protected static ReadyTask ready(String taskId, AbortFacts facts = AbortFacts.none(), boolean returned = false) {
        new ReadyTask(new TaskRef(taskId), facts, returned, false, UntrustedText.tracker('fixture title'))
    }

    protected static TrackerTask trackerTask(String taskId) {
        new TrackerTask(
                new TaskRef(taskId), new TaskSnapshot(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none(), false)
    }
}
