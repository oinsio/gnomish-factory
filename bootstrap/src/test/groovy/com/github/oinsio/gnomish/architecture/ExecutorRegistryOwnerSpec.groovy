package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * Design D8, D10 of define-executor-contract, the {@code ExecutorRegistry} row (task 7.3); design D18
 * of migrate-verify-to-executor-contract, the {@code ExecutorRegistry} row as amended 2026-11-10 (task
 * 3.6): an executor is chosen and built in one place. The registry is built only by the selector, the
 * stage dispatch is the only file that wraps an entry into a round wrapper, and each built-in is
 * constructed only in its registry entry's binding — the five verdict built-ins and the {@code
 * command} factory in {@code BuiltInEntries}, the two SCM built-ins in {@code ScmBuiltIns} of {@code
 * :adapters:scm} — so no run path builds a built-in beside the registry. The SCM connections are read
 * through the one reader only by the registry assembly, and the four built-in names that mean nothing else are spelled only by their one {@code
 * :domain} set. The engine's one port routes a check round to the check dispatch, which binds it and
 * puts it behind the run's pin-check (task 4.2f). A law's program binds its check only through its
 * entry's fresh-box binding, and the credential an {@code http} check names in its own settings is read
 * for the run's scrub set only by the built-ins' credential-names owner (task 5.3). The agent arm's own construction is pinned to the selector by {@link
 * ProgressListenerWiringSpec}. Since task 5.2 no check reaches its executor any other way: the
 * check-provider SPI is discovered by nobody, task 6.1 removed it and its client type from the
 * plugin api, so no production source names them, and {@code ServiceLoader} serves the tracker and the sandbox
 * bindings alone. A whole-tree text scan, comments stripped, that asserts it reached every
 * allowlisted file.
 *
 * <p>{@code "http"} and {@code "command"} are not in the name table: the first is also a URL scheme
 * ({@code EgressAdmission}, {@code OriginUrlNormalizer}), the second also a stage type and a manifest
 * token ({@code StageDefinitionMapper}, {@code VerifyCheckDtoDeserializer}) — words of another
 * vocabulary, not the registry names, which {@code HttpExecutor.NAME} and {@code
 * CommandExecutorFactory.NAME} take from the set.
 *
 * <p>FR9, FR10 of define-executor-contract; FR8 of add-command-executor; FR1, FR2, FR3, FR13, FR16,
 * NFR-S1 of migrate-verify-to-executor-contract.
 */
class ExecutorRegistryOwnerSpec extends Specification {

    private static final String APP = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/'
    private static final String REGISTRY = APP + 'ExecutorRegistry.java'
    private static final String SELECTOR = APP + 'ExecutorAdapterSelector.java'
    private static final String DISPATCH = APP + 'ExecutorDispatch.java'
    private static final String BUILT_INS = APP + 'BuiltInEntries.java'
    private static final String CHECK_EQUIPMENT = APP + 'CheckEquipment.java'
    private static final String SUBSECTIONS = APP + 'BuiltInCheckSubsections.java'
    private static final String RUN_DESCRIBE = APP + 'RunDescribe.java'
    private static final String ASSEMBLER = APP + 'RunAssembler.java'
    private static final String CHECK_DISPATCH = APP + 'CheckDispatch.java'
    private static final String RUN_LAW = APP + 'RunLaw.java'
    private static final String PIN_CHECK = 'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/PinCheck.java'
    private static final String COMMAND_FACTORY =
    'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/command/CommandExecutorFactory.java'
    private static final String SCM = 'adapters/scm/src/main/java/com/github/oinsio/gnomish/adapter/scm/'
    private static final String SCM_BUILT_INS = SCM + 'builtin/ScmBuiltIns.java'
    private static final String TRACKER_DISCOVERY =
    'bootstrap/src/main/java/com/github/oinsio/gnomish/adapter/tracker/TrackerAdapterDiscovery.java'
    private static final String BINDING_DISCOVERY =
    'bootstrap/src/main/java/com/github/oinsio/gnomish/adapter/sandbox/SandboxBindingDiscovery.java'
    private static final String NAMES =
    'domain/src/main/java/com/github/oinsio/gnomish/domain/pipeline/BuiltInExecutors.java'

    /** A call of the one connection reader: it always hands over the built-in's endpoint declaration. */
    private static final Pattern CONNECTION_READ = ~/\.read\([^;]*\.ENDPOINT\b/

    @Shared
    List<File> sources = RepoSourceTree.productionSources()

    def "the scan reached the tree, and every owner with it"() {
        expect:
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collect {
            RepoSourceTree.relative(it)
        }.containsAll([
            REGISTRY,
            SELECTOR,
            DISPATCH,
            BUILT_INS,
            CHECK_EQUIPMENT,
            SUBSECTIONS,
            RUN_DESCRIBE,
            ASSEMBLER,
            CHECK_DISPATCH,
            RUN_LAW,
            PIN_CHECK,
            COMMAND_FACTORY,
            SCM_BUILT_INS,
            TRACKER_DISCOVERY,
            BINDING_DISCOVERY,
            NAMES
        ])
    }

    def "FR9, FR13: '#form' is spelled in production only by #owners"() {
        when:
        def spellers = sources.findAll {
            RepoSourceTree.code(it).contains(form)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        spellers == owners.sort()

        where:
        form || owners
        'new ProgramStageExecutor(' || [DISPATCH]
        'new ExecutorDispatch(' || [SELECTOR]
        'ExecutorRegistry.of(' || [SELECTOR]
        'new CommandExecutorFactory(' || [BUILT_INS]
        'new CommandStageExecutor(' || [COMMAND_FACTORY]
        'CommandExecutorFactory.NAME' || [DISPATCH]
        'new FilesExistExecutor(' || [BUILT_INS]
        'new ShellExecutor(' || [BUILT_INS]
        'new HttpExecutor(' || [BUILT_INS]
        'new GithubActionsExecutor(' || [SCM_BUILT_INS]
        'new ScmStatusExecutor(' || [SCM_BUILT_INS]
        'new ScmConnectionReader(' || [CHECK_EQUIPMENT]
        // Task 5.3, D9 (the built-ins' credential names row; NFR-S1): the name an http check gives in
        //     its own auth setting is read for the scrub and never-allowlist sets by its one owner.
        'HttpExecutor.credentials(' || [SUBSECTIONS]
        // Task 5.3, D14, D18: a program's check is bound only by its registry entry, through a fresh
        //     box per request, and its placement is judged only at run assembly.
        'new ProgramCheckBinding(' || [REGISTRY]
        'ProgramCheckPlacement.refusals(' || [RUN_DESCRIBE]
        // Task 5.2, D9 (ExecutorRegistry row): ServiceLoader discovers the tracker and the sandbox
        //     bindings only — no check is discovered.
        'ServiceLoader.load(' || [
            TRACKER_DISCOVERY,
            BINDING_DISCOVERY
        ]
        // Task 4.2f, D17, D18 as amended 2026-11-12: the engine's one port is the route the selector
        //     builds, the run's only EnginePorts is the assembler's, a check round is bound only by the
        //     check dispatch, and every bound executor passes the run's pin-check there.
        'new CheckRoundRoute(' || [SELECTOR]
        'new CheckDispatch(' || [SELECTOR]
        'new CheckRound(' || [CHECK_DISPATCH]
        'new EnginePorts(' || [ASSEMBLER]
        'stageRounds.port()' || [ASSEMBLER]
        '.guarding(' || [CHECK_DISPATCH]
        'new PinCheck(' || [RUN_LAW]
        'new PinCheckedExecutor(' || [PIN_CHECK]
        '"files-exist"' || [NAMES]
        '"shell"' || [NAMES]
        '"github-actions"' || [NAMES]
        '"scm-status"' || [NAMES]
    }

    def "FR13, NFR-S2: the one connection reader is read only by the registry assembly"() {
        when:
        def readers = sources.findAll {
            CONNECTION_READ.matcher(RepoSourceTree.code(it)).find()
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        readers == [SCM_BUILT_INS]
    }

    // Task 5.2 of migrate-verify-to-executor-contract (G1, M1, D9): the three check ports left the
    //     engine; task 6.1 took the check-provider SPI out of the plugin api and its sample, so no
    //     production source spells any of them — no raw form survives.
    def "G1, M1: '#type' is spelled by no production source"() {
        given:
        def word = Pattern.compile('\\b' + type + '\\b')

        when:
        def spellers = sources.findAll {
            word.matcher(RepoSourceTree.code(it)).find()
        }
        .collect { RepoSourceTree.relative(it) }

        then:
        spellers.isEmpty()

        where:
        type << [
            'ExternalCheckClient',
            'CheckClientFactory',
            'CheckClientContext',
            'ExternalCheckPinContributor',
            'BuiltinCheckRunner',
            'CommandCheckRunner'
        ]
    }
}
