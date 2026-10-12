package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.github.oinsio.gnomish.app.port.git.OriginContact
import com.github.oinsio.gnomish.app.port.pipeline.BoundConfiguration
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.baseref.AllowedBase
import com.github.oinsio.gnomish.baseref.AllowedBases
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.BasePattern
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.Specification

/**
 * FR2, FR13, NFR-P1 of add-base-ref-resolution (design D14): the trusted tier — the
 * {@code task-branch.base} section among it — is bound once, at a start of {@code serve} or
 * {@code take}, from the refreshed default branch; a claim resolves under that binding and reads
 * the default branch no more. {@link TrustedTierStartup} binds, {@link FreshClaimBaseBinding}
 * resolves each claim under the {@link TrustedBaseContext} built from it, exactly as the daemon
 * wires them.
 */
class TrustedBaseLifetimeSpec extends Specification {

    private static final Path DIR = Paths.get('/repo')
    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final TaskState STATE = TaskState.atStageStart('plan')

    private BaseRefGit baseRefs = Mock()
    private PipelineSource pipelineSource = Mock()
    private Tracker tracker = Mock()

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('plan', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
            'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }

    private static BaseDefinition allowing(String... patterns) {
        new BaseDefinition(AllowedBases.of(patterns.collect {
            AllowedBase.of(BasePattern.compile(it))
        }), null)
    }

    /** One start: discovery, the refresh of the default branch, and the definition at its tip. */
    private TrustedBaseContext start() {
        def law = TrustedTierStartup.bind(DIR, baseRefs, pipelineSource, [:])
        new TrustedBaseContext(law.base(), law.defaultBranch())
    }

    private static TrackerTask selecting(String base) {
        new TrackerTask(REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none(), false, TaskDesignators.of('base', Designator.single(base)))
    }

    // FR2, FR13, NFR-P1 of add-base-ref-resolution: the binding a running daemon resolves under is
    //     its startup's; the merged entry participates from the next start on.
    // Scenario: git/base-ref-resolution / A merged change to the allowed bases waits for the next start
    // Scenario: tracker/tracker-take / The base configuration is bound once at startup
    def "FR13: a claim parks under the allowed bases bound at startup, reads no default branch, and the next start accepts the entry"() {
        given: 'the daemon started while the default branch allowed release/* only'
        def lawCommit = new ObjectId('b' * 40)
        baseRefs.discoverDefaultBranch(DIR) >> new DefaultBranchDiscovery.Discovered(new DefaultBranch('main'))
        baseRefs.refresh(DIR, 'main') >> new BaseRefreshOutcome.Refreshed('main', lawCommit.hex(), BaseRefKind.BRANCH, OriginContact.CONTACTED)
        pipelineSource.bindConfiguration(_, _) >>> [
            new BoundConfiguration(new LoadOutcome.Loaded(pipeline()), allowing('release/*'), lawCommit),
            new BoundConfiguration(new LoadOutcome.Loaded(pipeline()), allowing('release/*', 'hotfix/*'), lawCommit),
        ]
        def running = start()

        when: 'a human merged hotfix/* to the default branch meanwhile, and a task selecting hotfix/2 is claimed'
        def claimed = FreshClaimBaseBinding.bind(baseRefs, DIR,
                new FreshClaimBaseBinding.Request(null, selecting('hotfix/2'), running), STATE, tracker)

        then: 'the running daemon parks it as disallowed, under its startup binding'
        claimed instanceof FreshClaimBaseBinding.Parked
        1 * tracker.park(REF, ParkReason.INFRA, { it.contains('hotfix/2') })

        and: 'no default-branch fetch or discovery ran on the claim path'
        0 * baseRefs.discoverDefaultBranch(_)
        0 * baseRefs.refresh(*_)
        0 * pipelineSource.bindConfiguration(*_)

        when: 'serve starts again and the same task is claimed'
        def restarted = start()
        def reclaimed = FreshClaimBaseBinding.bind(baseRefs, DIR,
                new FreshClaimBaseBinding.Request(null, selecting('hotfix/2'), restarted), STATE, tracker)

        then: 'the new start binds the merged section, and the entry is accepted'
        1 * baseRefs.refresh(DIR, 'hotfix/2') >> new BaseRefreshOutcome.Refreshed('hotfix/2', 'c0ffee', BaseRefKind.BRANCH, OriginContact.CONTACTED)
        reclaimed instanceof FreshClaimBaseBinding.Bound
        0 * tracker.park(*_)
    }
}
