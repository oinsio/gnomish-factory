package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.github.oinsio.gnomish.app.port.pipeline.BoundConfiguration
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.Specification

/**
 * TrustedTierStartup: the startup read of {@code serve}/{@code take} (FR5, FR13, UX5, design D14,
 * D15 of add-base-ref-resolution) — discover the repository default branch from {@code origin},
 * refresh it, and load the whole definition at the refreshed tip. Both failure classes end the
 * process before any claim: a default branch that cannot be established or refreshed is a coded
 * {@link OperatorEvent#STARTUP_DEFAULT_BRANCH_UNBOUND} ERROR and a thrown {@link
 * DefaultBranchUnboundException}; a definition that fails to load is a {@link
 * PipelineLoadFailedException}, unchanged from the pre-existing exit-3 shape (FR12 of
 * add-manual-run) and asserted only for propagation here — {@code ConfigError} rendering is
 * {@code PipelineSource}'s own spec's job.
 */
class TrustedTierStartupSpec extends Specification {

    private static final Path DIR = Paths.get('/repo')
    private static final ObjectId LAW_COMMIT = new ObjectId('b' * 40)
    private static final Map<String, TrackerAdapterFactory> REGISTRY = [:]

    private BaseRefGit baseRefs = Mock()
    private PipelineSource pipelineSource = Mock()

    private static PipelineDefinition pipeline() {
        def stage = new StageDefinition(
                'plan', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    // FR13, D14: the common case — origin names a branch, the fetch lands cleanly, and the
    // definition at that tip binds the trusted tier for the process lifetime.
    def "binds the trusted tier from the discovered branch and refreshed tip when the definition loads cleanly"() {
        given:
        def definition = pipeline()
        def base = BaseDefinition.none()

        when:
        def law = TrustedTierStartup.bind(DIR, baseRefs, pipelineSource, REGISTRY)

        then:
        1 * baseRefs.discoverDefaultBranch(DIR) >> new DefaultBranchDiscovery.Discovered('develop')
        1 * baseRefs.refresh(DIR, 'develop') >> new BaseRefreshOutcome.Refreshed('develop', LAW_COMMIT.hex(), BaseRefKind.BRANCH)
        1 * pipelineSource.bindConfiguration(LawBinding.atRevision(DIR, LAW_COMMIT.hex()), _) >>
                new BoundConfiguration(new LoadOutcome.Loaded(definition), base, LAW_COMMIT)

        and:
        law.definition() == definition
        law.base() == base
        law.defaultBranch() == 'develop'
        law.lawCommit() == LAW_COMMIT
    }

    // FR5, FR13, D14: no remote, no default branch, or an origin that never answered — three
    // distinct discovery facts, all ending fail-fast before any refresh or load is attempted.
    def "ends fail-fast with a coded GF134 ERROR when default-branch discovery does not yield a branch: #outcome"() {
        given:
        def logs = LogCaptureSupport.attach(TrustedTierStartup)

        when:
        TrustedTierStartup.bind(DIR, baseRefs, pipelineSource, REGISTRY)

        then:
        1 * baseRefs.discoverDefaultBranch(DIR) >> outcome
        0 * baseRefs.refresh(*_)
        0 * pipelineSource.bindConfiguration(*_)

        and:
        def error = thrown(DefaultBranchUnboundException)
        error.message.contains(fragment)

        and: 'FR16 of harden-logging-observability: the one ERROR of this failure class is coded'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.STARTUP_DEFAULT_BRANCH_UNBOUND.head())
        }
        event != null
        event.level == Level.ERROR

        cleanup:
        logs.detach()

        where:
        outcome | fragment
        new DefaultBranchDiscovery.NoRemote() | "no 'origin' remote"
        new DefaultBranchDiscovery.Undetermined('empty repository') | 'empty repository'
        new DefaultBranchDiscovery.Unavailable('connection refused') | 'connection refused'
    }

    // FR5, FR6, FR9, D14: a discovered branch that cannot be refreshed — a deterministic refusal
    // or an origin that never answered the fetch — is the same fail-fast class as discovery.
    def "ends fail-fast with a coded GF134 ERROR when the discovered branch cannot be refreshed: #outcome"() {
        given:
        baseRefs.discoverDefaultBranch(DIR) >> new DefaultBranchDiscovery.Discovered('develop')
        def logs = LogCaptureSupport.attach(TrustedTierStartup)

        when:
        TrustedTierStartup.bind(DIR, baseRefs, pipelineSource, REGISTRY)

        then:
        1 * baseRefs.refresh(DIR, 'develop') >> outcome
        0 * pipelineSource.bindConfiguration(*_)

        and:
        def error = thrown(DefaultBranchUnboundException)
        error.message.contains(fragment)

        and:
        logs.list.any {
            it.formattedMessage.startsWith(OperatorEvent.STARTUP_DEFAULT_BRANCH_UNBOUND.head()) && it.level == Level.ERROR
        }

        cleanup:
        logs.detach()

        where:
        outcome | fragment
        new BaseRefreshOutcome.Refused('ref does not exist') | 'ref does not exist'
        new BaseRefreshOutcome.Unavailable('timed out') | 'timed out'
    }

    // FR12 of add-manual-run, unchanged: a definition that fails to load at the refreshed tip
    // keeps the pre-existing exit-3 shape. No GF134 is logged — this is not a base-binding failure.
    def "propagates PipelineLoadFailedException, uncoded, when the definition fails to load at the refreshed tip"() {
        given:
        def errors = [
            new ConfigError('pipeline.yaml', 'stages', "missing required field 'stages'")
        ]
        baseRefs.discoverDefaultBranch(DIR) >> new DefaultBranchDiscovery.Discovered('develop')
        baseRefs.refresh(DIR, 'develop') >> new BaseRefreshOutcome.Refreshed('develop', LAW_COMMIT.hex(), BaseRefKind.BRANCH)
        pipelineSource.bindConfiguration(_, _) >>
                new BoundConfiguration(new LoadOutcome.Invalid(errors), BaseDefinition.none(), LAW_COMMIT)
        def logs = LogCaptureSupport.attach(TrustedTierStartup)

        when:
        TrustedTierStartup.bind(DIR, baseRefs, pipelineSource, REGISTRY)

        then:
        def error = thrown(PipelineLoadFailedException)
        error.renderedErrors() == [
            "pipeline.yaml: stages: missing required field 'stages'"
        ]

        and: 'the definition-load failure class carries no base-binding code'
        logs.list.every {
            !it.formattedMessage.startsWith(OperatorEvent.STARTUP_DEFAULT_BRANCH_UNBOUND.head())
        }

        cleanup:
        logs.detach()
    }
}
