package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.github.oinsio.gnomish.app.port.git.OriginContact
import com.github.oinsio.gnomish.app.port.pipeline.BoundConfiguration
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.gitobjects.ObjectId
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link TrackerWiring} (design D2 of collapse-composition-roots, the {@code TrackerWiring} row):
 * the one owner of the adapter registry, the credential seam and the definition source, and of the
 * sequence they are used in. Folds the former {@code TrackerResolutionSpec} (task 5.13 of
 * add-tracker-port) and {@code TakeRefResolutionSpec} (task 8.7 of split-into-modules), whose
 * static helpers this class absorbed.
 *
 * <p>Implements FR4, NFR-S1 of collapse-composition-roots; FR9, FR17 of add-tracker-port; FR4 of
 * fix-claim-epoch-fence.
 */
class TrackerWiringSpec extends Specification {

    private static final TrackerConfig GITHUB = new TrackerConfig('github', 3)
    private static final MapSecretsProvider SECRETS = MapSecretsProvider.NONE

    @TempDir
    Path projectDir

    private static TrackerWiring wiring(Map<String, TrackerAdapterFactory> registry,
            PipelineSource source = TrackerValidatorStub.acceptingGithubSource()) {
        new TrackerWiring(registry, SECRETS, source)
    }

    // FR4 of fix-claim-epoch-fence: the claiming funnel builds the adapter over the bundle's own
    //     tenure record AND records into that same record, so a claim and the commits stamped under
    //     it can never be described by two different books.
    def "resolveTracker builds the adapter over the bundle's tenure record and records claims into it"() {
        given: 'a factory that answers the epoch-aware create, and a tenure record from a bundle'
        def trackerConfig = new TrackerConfig('fixture', 3, [:])
        def book = new ClaimEpochBook()
        def live = Mock(Tracker)
        def factory = Mock(TrackerAdapterFactory)
        def instanceId = InstanceId.generate('gnomish-factory')

        when:
        def resolved = wiring([fixture: factory]).resolveTracker(factory, trackerConfig, instanceId, book)

        then: 'the adapter was handed the SAME book and the wiring\'s own secrets, so its writers stamp the live tenure'
        1 * factory.create(SECRETS, trackerConfig, instanceId.value(), book) >> live

        when: 'a claim is acquired through the resolved tracker'
        def claimed = resolved.claim(new TaskRef('PROJ-1'), instanceId.value())

        then: 'the epoch the tracker issued landed in that same book'
        1 * live.claim(new TaskRef('PROJ-1'), instanceId.value()) >> new ClaimResult.Acquired(new ClaimEpoch(7))
        claimed == new ClaimResult.Acquired(new ClaimEpoch(7))
        book.epochFor('PROJ-1').orElse(null) == new ClaimEpoch(7)
    }

    def "resolveFactory returns the factory registered for the configured type"() {
        given:
        def factory = Mock(TrackerAdapterFactory)

        expect:
        wiring([github: factory, jira: Mock(TrackerAdapterFactory)]).resolveFactory(GITHUB).is(factory)
    }

    // FR17: the refusal names the unknown type and the registered ones as a stable, sorted list,
    //     so the operator hint is actionable and reads the same on every run.
    def "resolveFactory refuses an unregistered type, naming the supported ones sorted"() {
        given:
        def registry = [inmemory: Mock(TrackerAdapterFactory), github: Mock(TrackerAdapterFactory)]

        when:
        wiring(registry).resolveFactory(new TrackerConfig('unknown-type', 3, [:]))

        then:
        def ex = thrown(UsageException)
        ex.message.contains('unknown-type')
        ex.message.contains('github, inmemory')
    }

    def "supportedTypes renders the registered type keys sorted and comma-joined"() {
        expect:
        wiring(registry).supportedTypes() == expected

        where:
        registry || expected
        [:] || ''
        [github: Mock(TrackerAdapterFactory)] || 'github'
        [inmemory: Mock(TrackerAdapterFactory), github: Mock(TrackerAdapterFactory)] || 'github, inmemory'
    }

    // FR1 of add-board-command, design D8: a reader loads the working-tree pipeline, is built with
    //     the wiring's secrets over the command's minted instance id, and is left unwrapped — it never claims.
    def "resolveReadOnly loads the project's pipeline and builds a plain reader from its tracker section"() {
        given: 'a project whose .gnomish/ names the github tracker'
        GnomishProjectFixture.writeGnomishProject(projectDir)
        def tracker = Mock(Tracker)
        def factory = Mock(TrackerAdapterFactory)
        def readerId = InstanceId.generate('widgets-reader-instance')

        when:
        def resolution = wiring([github: factory]).resolveReadOnly(projectDir, readerId)

        then: 'the three-argument create — no tenure record — with the wiring\'s own secrets'
        1 * factory.create(SECRETS, { TrackerConfig c ->
            c.type() == 'github'
        }, readerId.value()) >> tracker
        resolution.tracker().is(tracker)
        resolution.trackerConfig().type() == 'github'
    }

    def "resolveReadOnly refuses when no factory is registered for the project's tracker type"() {
        given:
        GnomishProjectFixture.writeGnomishProject(projectDir)

        when:
        wiring([:]).resolveReadOnly(projectDir, InstanceId.generate('widgets-reader-instance'))

        then:
        def ex = thrown(UsageException)
        ex.message.contains("unknown tracker type 'github'")
    }

    // FR9: a canonical ref is nobody's business but its own — it is wrapped as-is, and the adapter
    //     registry is never consulted, so an unconfigured tracker type cannot break it.
    def "resolveRef wraps an already-canonical ref verbatim without consulting the registry"() {
        expect:
        wiring([:]).resolveRef('github:owner/repo#42', GITHUB) == new TaskRef('github:owner/repo#42')
    }

    // FR9: the short-ref shapes the parser accepts are expanded by the registered adapter, which is
    //     what supplies the repository coordinates a bare number lacks — and only that adapter is asked.
    def "resolveRef expands a short ref #shortRef through the adapter registered for the declared type only"() {
        given:
        def expanded = new TaskRef('github:owner/repo#42')
        def github = Mock(TrackerAdapterFactory)
        def jira = Mock(TrackerAdapterFactory)

        when:
        def ref = wiring([github: github, jira: jira]).resolveRef(shortRef, GITHUB)

        then:
        1 * github.expandRef(GITHUB, shortRef) >> expanded
        0 * jira._
        ref == expanded

        where:
        shortRef << ['42', '#42']
    }

    // FR9: a short ref with no adapter for the declared type cannot be expanded at all, and that is
    //     an operator-facing configuration error naming the ref, the unknown type and what IS supported.
    def "resolveRef refuses a short ref when no adapter is registered for the declared type"() {
        when:
        wiring([:]).resolveRef('42', GITHUB)

        then:
        def ex = thrown(UsageException)
        ex.message.contains("cannot expand short ref '42'")
        ex.message.contains("unknown tracker type 'github'")
    }

    // FR9, design D8 of add-tracker-port; NFR-S1: the check is asked with the wiring's secrets,
    //     which the caller never holds — the wiring grants their use, not their possession.
    def "refuseForeignRef asks the factory with the wiring's own secrets and relays its verdict"() {
        given:
        def factory = Mock(TrackerAdapterFactory)
        def ref = new TaskRef('github:other/repo#1')

        when:
        def refusal = wiring([github: factory]).refuseForeignRef(factory, GITHUB, ref)

        then:
        1 * factory.refuseForeignRef(SECRETS, GITHUB, ref) >> Optional.of('ref names another repository')
        refusal == Optional.of('ref names another repository')
    }

    // FR13 of add-base-ref-resolution: the startup law is bound through the wiring's OWN
    //     definition source — the one every fresh claim reads its task tier through — so the two
    //     reads cannot come from different sources (the sequence itself is TrustedTierStartupSpec's).
    def "bindStartupLaw binds the trusted tier through the wiring's own definition source"() {
        given:
        def source = Mock(PipelineSource)
        def baseRefs = Mock(BaseRefGit)
        def lawCommit = new ObjectId('b' * 40)
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [])

        when:
        def law = wiring([:], source).bindStartupLaw(projectDir, baseRefs)

        then:
        1 * baseRefs.discoverDefaultBranch(projectDir) >> new DefaultBranchDiscovery.Discovered(new DefaultBranch('develop'))
        1 * baseRefs.refresh(projectDir, 'develop') >>
                new BaseRefreshOutcome.Refreshed('develop', lawCommit.hex(), BaseRefKind.BRANCH, OriginContact.CONTACTED)
        1 * source.bindConfiguration(LawBinding.atRevision(projectDir, lawCommit.hex()), _) >>
                new BoundConfiguration(new LoadOutcome.Loaded(definition), BaseDefinition.none(), lawCommit)

        and:
        law.definition() == definition
        law.lawCommit() == lawCommit
    }

    def "pipelineSource hands out the source the wiring was built with"() {
        given:
        def source = TrackerValidatorStub.acceptingGithubSource()

        expect:
        wiring([:], source).pipelineSource().is(source)
    }
}
