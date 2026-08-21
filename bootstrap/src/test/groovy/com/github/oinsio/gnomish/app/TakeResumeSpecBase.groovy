package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import java.time.Clock

/**
 * Shared fixture for {@link TakeResumeRunner} specs (task 5.6): adds tracker stubbing and
 * {@link TakeResumeRunner}-specific helpers on top of {@link ResumeSpecFixtureBase}'s
 * bare-repo-backed clone — mirroring {@code GitResumeSpecBase} for the manual-run resume
 * machinery this class reuses.
 *
 * <p>Implements FR9, FR12, D3 of add-tracker-port.
 */
abstract class TakeResumeSpecBase extends ResumeSpecFixtureBase {

    protected static final TaskRef REF = new TaskRef('PROJ-1')
    protected static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    protected static final int ABORT_THRESHOLD = 3

    Tracker tracker = Mock()

    /**
     * The claim holder {@link #tracker}'s {@code fetchTask} stub reports (defaults to this
     * instance's own id, i.e. "still ours and alive"). A test can flip this before the round that
     * should be observed as revoked runs, without needing a second, order-dependent {@code >>}
     * stub competing with this one.
     */
    protected String workingHolder = INSTANCE.value()

    /**
     * The open-front count {@link #tracker}'s {@code listOpen} stub reports (FR6, D5 of
     * add-factory-serve): defaults to no open fronts so specs unconcerned with the WIP limit are
     * unaffected. A test can reassign this field before the run it exercises, mirroring {@link
     * #workingHolder} — the underlying stub is a closure read at call time, so a plain {@code >>}
     * reassignment in a test body would otherwise silently lose to this one (first-registered
     * unbounded interactions win ties, not the most recently declared).
     */
    protected List<OpenTask> openFronts = []

    def setup() {
        tracker.fetchTask(_) >> {
            new TrackerTask(
            REF, new TaskSnapshot('PROJ-1', 'title', 'body'),
            new TrackerTaskState.Working(workingHolder), AbortFacts.none(), false)
        }
        tracker.listOpen() >> { openFronts }
    }

    protected TakeResumeRunner newTakeResumeRunner(
            InputStream input = new ByteArrayInputStream((System.lineSeparator() * 20).getBytes('UTF-8')),
            FactoryProperties factoryProperties = testProperties(),
            List<String> credentialEnvVarsToScrub = [],
            ClaimLossFlag claimLossFlag = new ClaimLossFlag()) {
        def assembly = newAssembly(input, System.out, factoryProperties)
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeResumeRunner(
                assembly, TaskGitFixture.real(), worktreesRoot, 'taskId', abortHandler, ABORT_THRESHOLD, credentialEnvVarsToScrub, claimLossFlag)
    }

    /**
     * The escalation dialog bound to HOST resume mechanics — the seam {@link TakeDecisionResume}
     * dispatches through in either execution mode (design D8 of add-serve-sandbox-lifecycle).
     */
    protected TakeDecisionResume<ResumeBootstrap> newDecisionResume(
            TakeResumeRunner runner, PipelineDefinition definition) {
        new TakeDecisionResume<>(new HostResumeMechanics(runner, TaskGitFixture.real(), worktreesRoot, definition))
    }
}
