package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The identity of design D11 of supervise-daemon-loops-and-embed-dashboard (single-owner row 4):
 * the page inside {@code serve} reads its board through the configuration {@code serve} bound from
 * origin's default branch, never through the clone's checkout — so the WIP limit the page shows is
 * the limit the daemon's own eligibility runs on.
 *
 * <p>Proven on the real git medium, end to end: a bare {@code origin} whose default branch declares
 * one {@code wip-limit}, a clone whose checked-out {@code .gnomish/config.yaml} declares another in a
 * local commit origin never received (on top of {@link BareGitRepoFixture#addOrigin}'s adversarial
 * divergence), and a real {@code gnomish serve --dashboard --drain} assembled through the production
 * owners ({@link ServeCommands}, the real git adapters, {@code ServeDashboard}). The page's WIP stat
 * must carry origin's limit as its denominator. The second row picks an origin limit below the
 * mapper's default of 10, so a reader that dropped the declared limit altogether is red too.
 *
 * <p>Implements FR10 of supervise-daemon-loops-and-embed-dashboard (identity, single-owner row 4;
 * factory-serve "Checkout differs from origin").
 */
@Timeout(120)
class ServeDashboardBoundLimitSpec extends Specification
implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final String INSTANCE_NAME = 'factory-01' // FakeAgentSupport.propertiesFor's instance name

    @TempDir
    Path tempDir

    Path projectDir
    Path homeDir

    private Path serveDir() {
        homeDir.resolve("projects/${RegisteredCloneFixture.PROJECT}/serve/${INSTANCE_NAME}")
    }

    // FR10 (D11, single-owner row 4; factory-serve "Checkout differs from origin").
    def "FR10: serve --dashboard renders origin's wip-limit #originLimit, not the checkout's #checkoutLimit"() {
        given: "origin's default branch declares one limit"
        projectDir = initWorkingRepo(tempDir, 'project')
        writeMinimalProject(projectDir)
        writeLimit(projectDir, originLimit)
        commitAll(projectDir)
        addOrigin(projectDir, tempDir)
        homeDir = tempDir.resolve('home')

        and: 'the checkout declares another, in a local commit origin never received'
        writeLimit(projectDir, checkoutLimit)
        commitAll(projectDir, 'checkout-only limit')
        assert Files.readString(projectDir.resolve('.gnomish/config.yaml')).contains("wip-limit: $checkoutLimit")
        assert gitOutput(projectDir, 'show', "origin/${currentBranch(projectDir)}:.gnomish/config.yaml")
        .contains("wip-limit: $originLimit")

        and: 'an empty board, so the drain completes at once'
        def tracker = new InMemoryTracker()
        def properties = FakeAgentSupport.propertiesFor('plain-round')

        when:
        newDrainCommand(properties, newAssembly(properties),
                RegisteredCloneFixture.unregistered(homeDir, projectDir), fakeFactory(tracker))
                .run(args('serve', "--dir=$projectDir", '--drain', '--dashboard'))

        then: "the page's WIP stat is measured against origin's limit"
        def page = Files.readString(serveDir().resolve('dashboard.html'))
        wipStat(page).contains("0 / $originLimit<")
        !wipStat(page).contains("/ $checkoutLimit<")

        where:
        originLimit | checkoutLimit
        10 | 3
        4 | 3
    }

    /** Rewrites the tracker section of the minimal project's config with {@code wip-limit: limit}. */
    private static void writeLimit(Path projectDir, int limit) {
        def config = projectDir.resolve('.gnomish/config.yaml')
        def text = Files.readString(config).replaceAll(/(?m)^  wip-limit: \d+\n/, '')
        Files.writeString(config, text.replace('  type: github\n', "  type: github\n  wip-limit: $limit\n"))
    }

    /** The WIP stat's markup: from its label to the end of its stat block. */
    private static String wipStat(String page) {
        int start = page.indexOf('>WIP<')
        assert start >= 0: 'the page renders no WIP stat'
        page.substring(start, page.indexOf('</div></div>', start) + 1)
    }
}
