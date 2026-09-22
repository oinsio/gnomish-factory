package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR5, M3 of fix-claim-epoch-fence: the tenure record has one owner, and a git layer built without
 * one stays in the commands that never claim.
 *
 * <p>The defect this gate exists for had a green build: the epoch-recording decorator was applied
 * in a Spring bean and the writers' book came from another, so every end-to-end fixture — which
 * assembles the commands by hand, bypassing both beans — stamped nothing and recorded nothing. No
 * spec could see the fence firing on every legitimate reclaim, because in specs no epoch ever
 * existed. A convention ("fixtures should build through the composition root") is what the seven
 * fixtures already violated; this is the whole-tree scan that says so out loud, the same shape and
 * placement {@link BaseHeadDefaultBoundarySpec} uses — {@code :bootstrap} is the one module that
 * sees every layer at once.
 *
 * <p>Two rules, from design D4 of the change:
 *
 * <ul>
 *   <li><b>Rule 1, production and fixture sources.</b> A claimless git layer ({@code
 *       ClaimEpochSource.NONE}) belongs only to the plain-{@code run} wiring and the claimless
 *       fixture, and a tenure record ({@code new ClaimEpochBook()}) is built only where the
 *       process's one book is built.
 *   <li><b>Rule 2, test sources.</b> {@code ClaimEpochSource.NONE} belongs only to specs that
 *       never claim, and no file may hold both a {@code TaskGit} bundle and a book of its own —
 *       the two-book assembly whose halves cannot agree.
 * </ul>
 *
 * <p>The allowlists are paths, not patterns: a file that stops being exempt is deleted from the
 * list, and a new claiming assembly is outside it by default. Each rule asserts the scan really
 * reached every file it allows, so a renamed or moved exemption fails loudly instead of widening
 * the gate silently.
 */
class ClaimlessGitBoundarySpec extends Specification {

    /** The three trees rule 1 governs: production wiring plus the shared fixture module. */
    private static final List<String> OWNED_MAIN_TREES = [
        'application/src/main/',
        'bootstrap/src/main/',
        'test-fixtures/src/main/',
    ]

    /** The two trees rule 2 governs; each adapter's own test tree is outside it (single components, never claims). */
    private static final List<String> OWNED_TEST_TREES = [
        'application/src/test/',
        'bootstrap/src/test/',
    ]

    /**
     * The claimless fixtures: the only production sources that still choose a tenureless git
     * layer. {@code TaskSeedFixture} and {@code SeededCloneFixture} build single git components
     * directly ({@code GitTaskRepository}, {@code GitAttemptPersistence}) with {@code
     * ClaimEpochSource.NONE} for adapters/git and application usage/status specs — outside the
     * owned test trees, single components, never claims — never a {@code TaskGit} bundle.
     *
     * <p>The plain-{@code run} wiring left this list when it stopped choosing: {@code
     * ContainerRunSupportFactory} and {@code ManualRunRunner} each take a {@code ClaimEpochSource}
     * parameter now, so the caller supplies the tenure and the type carries the constraint the
     * exemption used to. Their rows were kept by a reach check that asked only whether the path
     * still existed, which is the staleness this gate's own javadoc promises to catch.
     */
    private static final List<String> CLAIMLESS_PRODUCTION = [
        'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/SeededCloneFixture.groovy',
        'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/TaskSeedFixture.groovy',
        'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/app/TaskGitFixture.groovy',
    ]

    /** Where the process's one tenure record is built: the Spring bean, and the fixture's stand-in for it. */
    private static final List<String> BOOK_OWNERS = [
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/ManualRunConfiguration.java',
        'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/app/TaskGitFixture.groovy',
    ]

    /**
     * The specs that legitimately drive a claimless git layer — every one of them dispositioned by
     * name in task 6.1 of fix-claim-epoch-fence, under design D3's rule: a spec that claims (drives
     * {@code take}, {@code serve}, or a tracker-backed resume) moves onto the bundle's tenure
     * record; a spec that never claims is listed here with the reason it never does.
     *
     * <p>Four reasons, and no others:
     *
     * <ul>
     *   <li><b>The read-only commands.</b> {@code status} and {@code usage} read a branch and write
     *       nothing; their specs seed one through a claimless writer on purpose, which is the
     *       distinction {@code TaskGitFixture.realClaimless()} exists to make. Only the specs that
     *       spell the token themselves are listed: {@code UsageCommandSpec} asks the fixture for
     *       the claimless variant by name and never names the source, so it needs no exemption.
     *   <li><b>Unit specs of the lease components.</b> {@code ClaimEpochBookSpec}, {@code
     *       ZombieFenceSpec} and {@code RevocationHandlerSpec} construct a source or a book as
     *       their SUBJECT, not as part of an assembly.
     *   <li><b>The plain-{@code run} and {@code run --resume} paths.</b> {@code gnomish run} claims
     *       nothing, so the container and git-mode runner specs, the run-mode resume specs, and the
     *       sandbox lifecycle E2E specs drive writers that hold no tenure — the same shape the
     *       production {@code run} wiring has.
     *   <li><b>Claimless branch readers.</b> The two kill-point worlds build a {@code
     *       GitTaskBranches} only to classify a tip. Classification takes no epoch at all since FR1,
     *       and these readers never write, so there is no tenure for them to stamp from.
     * </ul>
     */
    private static final List<String> CLAIMLESS_SPECS = [
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ContainerGitModeRunnerSpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ContainerLifecycleCoverageGapsE2ESpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ContainerModeResumeE2ESpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ContainerResumeSpecBase.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ContainerRunSupportSpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ContainerRunTerminationSweepSpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ContainerTerminalDriveSpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/GitKillResumeSalvageCompletionSpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/GitModeMidRoundPushSpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/ManualRunRunnerSpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/SandboxLifecycleCrossInstanceE2ESpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/SandboxLifecycleLaunchRaceE2ESpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/SandboxLifecycleLegacyIdentityE2ESpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/SandboxLifecycleProjectScopingE2ESpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/SandboxLifecycleRemnantReapE2ESpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/SandboxLifecycleZombieE2ESpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/killpoint/CreationWorld.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/killpoint/KillPointWorld.groovy',
        'application/src/test/groovy/com/github/oinsio/gnomish/app/StatusCommandSpec.groovy',
        'application/src/test/groovy/com/github/oinsio/gnomish/app/StatusInterruptedHonestySpec.groovy',
        'application/src/test/groovy/com/github/oinsio/gnomish/app/StatusUsageReadOnlySpec.groovy',
        'application/src/test/groovy/com/github/oinsio/gnomish/app/ZombieFenceSpec.groovy',
        'application/src/test/groovy/com/github/oinsio/gnomish/app/lease/ClaimEpochBookSpec.groovy',
        'application/src/test/groovy/com/github/oinsio/gnomish/app/take/RevocationHandlerSpec.groovy',
    ]

    /**
     * This gate's own sources: the rule, the spec that seeds it, and this file necessarily spell
     * every token, as the strings they scan for. Every whole-tree scan has to exempt itself;
     * naming the files rather than filtering by package keeps the exemption as narrow as the rest
     * of the allowlists.
     */
    private static final List<String> THIS_GATE = [
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/ClaimlessGitBoundarySpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/ClaimlessGitDetectorSpec.groovy',
        'bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/ClaimlessGitRule.groovy',
    ]

    private static final String CLAIMLESS_SOURCE = ClaimlessGitRule.CLAIMLESS_SOURCE
    private static final String FRESH_BOOK = ClaimlessGitRule.FRESH_BOOK

    // FR5, M3: a claimless git layer outside the claimless commands is an assembly that claims and
    //     stamps nothing — the shape every end-to-end fixture had, and the reason the read-side
    //     fence could fire in production while every spec stayed green.
    def "FR5, M3: production and fixture sources build a claimless git layer only where nothing claims"() {
        given: 'every production and fixture source of the owned trees, comments stripped'
        def sources = RepoSourceTree.productionSources { path ->
            OWNED_MAIN_TREES.any { path.startsWith(it) }
        }

        and: 'the files each rule allows, and the files that break them'
        def reachedClaimless = ClaimlessGitRule.allowlistedSpelling(sources, CLAIMLESS_PRODUCTION, CLAIMLESS_SOURCE)
        def reachedOwners = ClaimlessGitRule.allowlistedSpelling(sources, BOOK_OWNERS, FRESH_BOOK)
        def claimlessOffenders = ClaimlessGitRule.offenders(sources, CLAIMLESS_SOURCE, CLAIMLESS_PRODUCTION)
        def bookOffenders = ClaimlessGitRule.offenders(sources, FRESH_BOOK, BOOK_OWNERS)

        expect: 'the scan really reached every allowlisted file'
        reachedClaimless == CLAIMLESS_PRODUCTION.toSet()
        reachedOwners == BOOK_OWNERS.toSet()

        and: 'no other production source builds a claimless git layer'
        claimlessOffenders.isEmpty()

        and: 'no other production source mints a tenure record of its own'
        bookOffenders.isEmpty()
    }

    // FR5, M3: a spec that claims must observe stamped commits and recorded epochs exactly as
    //     production does, so it builds through the owner; only a spec that never claims may hold
    //     a claimless layer, and no spec may hold a second book beside its bundle's.
    def "FR5, M3: test sources build through the owner unless they never claim"() {
        given: 'every test source of the owned trees, comments stripped'
        def sources = ownedTestSources()

        and: 'the files the rule allows, and the files that break it'
        def reachedClaimless = ClaimlessGitRule.allowlistedSpelling(sources, CLAIMLESS_SPECS, CLAIMLESS_SOURCE)
        def claimlessOffenders = ClaimlessGitRule.offenders(sources, CLAIMLESS_SOURCE, CLAIMLESS_SPECS)

        expect: 'the scan really reached every allowlisted file'
        reachedClaimless == CLAIMLESS_SPECS.toSet()

        and: 'no other spec builds a claimless git layer'
        claimlessOffenders.isEmpty()
    }

    // FR5, M3: two books in one file is the assembly whose halves cannot agree — the writers stamp
    //     from one record while the tracker fills the other, which is exactly how a fixture can
    //     look wired and still observe neither a stamp nor a live epoch.
    def "FR5, M3: no spec holds a tenure record beside the bundle it also builds"() {
        given: 'every test source of the owned trees, comments stripped'
        def sources = ownedTestSources()

        and: 'the files holding both a TaskGit bundle and a book of their own'
        def twoBookOffenders = twoBookFiles(sources)

        expect: 'none'
        twoBookOffenders.isEmpty()
    }

    /** Every test source rule 2 governs, minus this gate's own file. */
    private static List<File> ownedTestSources() {
        RepoSourceTree.testSources { path ->
            OWNED_TEST_TREES.any {
                path.startsWith(it)
            } && !THIS_GATE.contains(path)
        }
    }

    /** Every file that builds a bundle through the fixture AND mints a second tenure record beside it. */
    private static List<String> twoBookFiles(List<File> sources) {
        sources.findAll { file ->
            ClaimlessGitRule.spells(file, FRESH_BOOK) &&
            ClaimlessGitRule.spells(file, ClaimlessGitRule.BUNDLE_CONSTRUCTION)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()
    }
}
