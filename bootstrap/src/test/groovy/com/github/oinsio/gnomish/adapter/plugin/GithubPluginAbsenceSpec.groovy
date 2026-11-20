package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsExecutor
import com.github.oinsio.gnomish.adapter.scm.status.ScmStatusExecutor
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterConfiguration
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterDiscovery
import com.github.oinsio.gnomish.app.BuiltInCheckConfiguration
import com.github.oinsio.gnomish.app.ScmBuiltInRegistry
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.executorcontract.ExecutorName
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * Removing the github jar disables the github tracker, with no core source change, and the factory
 * still starts (FR12, M2 of add-plugin-architecture; M5 of migrate-verify-to-executor-contract;
 * github-plugin capability).
 *
 * <p>This is the acceptance test of the extraction. GitHub is a plugin only if the distribution
 * still works without it: the github tracker provider vanishes, the provider core itself ships —
 * {@code inmemory} for the tracker port — remains, the registries
 * the composition root derives still build, the executor registry still resolves the core
 * built-ins {@code github-actions} and {@code scm-status}, and not one core source file has to be
 * touched to say so. The last part is checked as a source-level gate rather than by argument: if
 * any core class reached the bundle's package, removing the jar would be a compile error rather
 * than a configuration change.
 *
 * <p>The removal is staged inside this JVM by hiding the artifact's service registrations
 * ({@link GithubArtifact}), which is exactly what discovery reads.
 *
 * <p>Implements FR12, M2 of add-plugin-architecture; FR13, M5 of migrate-verify-to-executor-contract.
 */
class GithubPluginAbsenceSpec extends Specification {

    /**
     * A code reference to the vendor bundle: its one remaining package (M5 of
     * migrate-verify-to-executor-contract). An import, a fully qualified name, a reflective string or
     * a package declaration all spell it; a {@code Github*} name alone does not, since core owns
     * {@code Github*} types of its own — the GitHub HTTP core in {@code :githubapi} and the {@code
     * github-actions} built-in in {@code :adapters:scm} (task 3.3a).
     */
    private static final Pattern BUNDLE_REFERENCE = ~/adapter\.tracker\.github\b/

    /** The bundle's own directory, the one place these references belong. */
    private static final String BUNDLE_DIR = 'adapters/github/'

    @Shared
    ClassLoader withoutGithub = GithubArtifact.hiddenFrom(getClass().classLoader)

    // FR12: "the factory still starts, the github tracker provider is absent" — and M2's other half,
    //     the reference adapter core ships is untouched by the extraction. M5 of
    //     migrate-verify-to-executor-contract: the bundle is the tracker only, so its absence leaves
    //     the two SCM built-ins resolvable — they are core's own, built by run assembly, not
    //     discovered (FR13, design D4, D18).
    // Scenario: plugin/github-plugin / Removing the github jar cleanly disables github providers
    // Scenario: plugin/github-plugin / inmemory stays in core as the reference adapter
    // Scenario: plugin/github-plugin / Removing the github jar keeps the core built-ins
    def "without the github artifact the tracker loses github while the executor registry still resolves #name"() {
        when:
        def trackers = TrackerAdapterDiscovery.discover(withoutGithub)
        def registry = ScmBuiltInRegistry.assemble()

        then: 'the tracker registry keeps its core provider and loses github'
        !trackers.containsKey('github')
        trackers.containsKey('inmemory')

        and: 'the same pass with the artifact present does find github — the removal is what did it'
        TrackerAdapterDiscovery.discover(getClass().classLoader).containsKey('github')

        and: 'the executor registry, which reads no service registration, still resolves the built-in'
        def entry = registry.resolve(new ExecutorName(name))
        entry.describe().egress() == egress
        type.isInstance(entry.executor(ScmBuiltInRegistry.check(name), CheckRef.of(0, ScmBuiltInRegistry.check(name)), AttemptCommitWorkspaces.at('abc')))

        where:
        name | type || egress
        BuiltInExecutors.GITHUB_ACTIONS | GithubActionsExecutor || GithubActionsExecutor.EGRESS
        BuiltInExecutors.SCM_STATUS | ScmStatusExecutor || ['127.0.0.1']
    }

    // FR12: "the factory still starts" — the composition root derives a validator registry for the
    //     tracker port from the discovered set, and the built-ins' params validators from core's own
    //     names (task 5.2 of migrate-verify-to-executor-contract): both still build without github.
    // Scenario: plugin/github-plugin / Removing the github jar cleanly disables github providers
    def "the composition root's derived registries still build without github"() {
        given:
        def trackers = TrackerAdapterDiscovery.discover(withoutGithub)

        when:
        def trackerValidators = new TrackerAdapterConfiguration().trackerSubsectionValidatorRegistry(trackers)
        def checkValidators = new BuiltInCheckConfiguration().checkParamsValidatorRegistry()

        then: 'no entry survives for the absent provider, and the built-ins still grade their settings'
        !trackerValidators.containsKey('github')
        checkValidators.keySet() == [
            BuiltInExecutors.HTTP,
            BuiltInExecutors.GITHUB_ACTIONS
        ] as Set
    }

    // M2: "with no core source change". Nothing outside the bundle may reach the bundle's package
    //     in code, so removing the jar is a packaging decision — never an edit to core.
    // Scenario: plugin/github-plugin / GitHub is discovered like any third-party plugin
    def "no production source outside the bundle references the bundle's package"() {
        given:
        def sources = RepoSourceTree.productionSources {
            !it.startsWith(BUNDLE_DIR)
        }

        expect: 'the scan really reached the source tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'and found no code reference to the bundle (prose in comments is not a dependency)'
        def offenders = sources.collectMany { file ->
            file.readLines().indexed(1)
            .findAll { number, line ->
                BUNDLE_REFERENCE.matcher(RepoSourceTree.codeOnly(line)).find()
            }
            .collect { number, line ->
                RepoSourceTree.relative(file) + ':' + number
            }
        }
        offenders.isEmpty()
    }

    // M5 of migrate-verify-to-executor-contract: the gate judges a reference to the bundle by its
    //     package, so core's own Github* types pass and every spelling of the package is caught.
    def "the source gate catches a reference to the bundle's package and passes core's own Github types: #line"() {
        expect:
        BUNDLE_REFERENCE.matcher(RepoSourceTree.codeOnly(line)).find() == caught

        where:
        line || caught
        'import com.github.oinsio.gnomish.adapter.tracker.github.GithubTracker;' || true
        'var f = new com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory();' || true
        'Class.forName("com.github.oinsio.gnomish.adapter.tracker.github.GithubTracker");' || true
        'package com.github.oinsio.gnomish.adapter.tracker.github;' || true
        'import com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsExecutor;' || false
        'import com.github.oinsio.gnomish.githubapi.GithubHttpClient;' || false
        'return new GithubActionsExecutor(runs, check, workspace);' || false
        ' * moved out of {@code adapter.tracker.github} by task 3.0a' || false
        'int x = 1; // see adapter.tracker.github' || false
    }
}
