package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR1, FR2, FR13 of migrate-verify-to-executor-contract (design D18 as amended 2026-11-12; {@code
 * testing.md}, "Fixtures assemble through production owners"): the run's check dispatch has one
 * production owner, {@code ExecutorAdapterSelector.stageRounds} — the registry over the run's own
 * built-ins and round equipment, the dispatch behind the run's pin-check, and the route outside the
 * stage chain — which {@link ExecutorRegistryOwnerSpec} pins for production. A spec that assembles
 * them by hand sees none of the production assembly's inputs: the check equipment's built-ins, the
 * run cell as the last registered commit, the described law. So a spec that claims the run's check
 * dispatch builds it through the selector, and the hand assemblies are the listed files below, each
 * with its reason. A whole-tree scan of the test sources, comments stripped, that asserts it reached
 * every listed file, so a moved or migrated file leaves the list instead of widening the gate.
 *
 * <p>The failure this exists for: the identity spec of the details mapping, {@code
 * ShellDetailsFlowSpec}, claimed "the run's check dispatch" over a dispatch a fixture built by hand
 * with fixture built-ins and no round cell, while {@code ManualRunAssemblySpec} pinned only the port's
 * class — a divergence of the production assembly no spec would have seen.
 */
class CheckDispatchAssemblyBoundarySpec extends Specification {

    private static final String APP = 'bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/'
    private static final String THIS_GATE =
    'bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/CheckDispatchAssemblyBoundarySpec.groovy'

    /** The hand assembly forms: the registry, the check dispatch, the engine's one port. */
    private static final List<String> FORMS = [
        'ExecutorRegistry.of(',
        'new CheckDispatch(',
        'new CheckRoundRoute('
    ]

    /** Each test source allowed to spell a form, and why it may. */
    private static final Map<String, String> HAND_ASSEMBLIES = [
        // The named hand-assembled variant: the dispatch and route specs, and the container kill-point
        //     media, whose stage chains are hand-built rounds over a local box with no SandboxRunPieces.
        (APP + 'HandAssembledCheckDispatch.groovy'): 'the one named hand-assembled dispatch',
        // Unit specs whose subject is the constructed class itself.
        (APP + 'CheckRoundRouteSpec.groovy'): 'subject: the route',
        (APP + 'CheckDispatchPinSpec.groovy'): 'subject: the dispatch behind a pin-check over a law commit',
        (APP + 'JudgeDispatchSpecBase.groovy'): 'subject: a judge check through the dispatch to the claude entry\'s check half (task 4.1 of extract-agent-round-runner)',
        (APP + 'ExecutorDispatchSpec.groovy'): 'subject: the stage dispatch over a registry',
        (APP + 'ExecutorRegistryCommandSpec.groovy'): 'subject: the registry\'s command entry',
        (APP + 'ExecutorRegistryEntrySpec.groovy'): 'subject: the registry\'s entries',
        (APP + 'ClaudeEntrySpec.groovy'): 'subject: the registry\'s claude entry (task 4.0a of extract-agent-round-runner)',
        // The SCM built-ins' entries over connections the production admission granted, against a
        //     scripted secrets port and a loopback platform the assembly's factory-home secrets cannot
        //     reach.
        (APP + 'BuiltInVerdictUsageSpec.groovy'): 'subject: each built-in\'s entry and its usage',
        (APP + 'ScmBuiltInsWithoutBundleSpec.groovy'): 'subject: SCM entries resolved with the bundle hidden',
        (APP + 'ScmBuiltInRegistry.groovy'): 'the SCM entries for the plugin specs, scripted secrets',
        ('bootstrap/src/test/groovy/com/github/oinsio/gnomish/domain/engine/PlatformCheckRounds.groovy'):
        'three-provider check rounds over an object-store law commit, scripted secrets',
        // The production gate spells every form as the string it scans for.
        ('bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/ExecutorRegistryOwnerSpec.groovy'):
        'the production owner gate'
    ]

    @Shared
    List<File> sources = RepoSourceTree.testSources {
        it != THIS_GATE
    }

    def "the scan reached the test tree, and every listed hand assembly spells a form"() {
        expect:
        sources.size() >= RepoSourceTree.KNOWN_TEST_SOURCES
        spellers(FORMS).toSet() == HAND_ASSEMBLIES.keySet()
    }

    def "FR1, FR13: '#form' is spelled in test sources only by listed hand assemblies"() {
        expect:
        HAND_ASSEMBLIES.keySet().containsAll(spellers([form]))

        where:
        form << FORMS
    }

    private List<String> spellers(List<String> forms) {
        sources.findAll { file ->
            def code = RepoSourceTree.code(file)
            forms.any { code.contains(it) }
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()
    }
}
