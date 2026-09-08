package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR4, FR8, UX2, UX3, task 6.5 of add-base-ref-resolution: a manual {@code gnomish run} without
 * {@code --base} stays offline — no remote is ever consulted for the base ref. There is no mock
 * seam to assert "zero interactions" against on this path: {@link
 * com.github.oinsio.gnomish.app.GitFreshTaskSupport#resolveManualBase} resolves through {@link
 * com.github.oinsio.gnomish.baseref.BaseRefResolver} in {@code MANUAL} mode with no {@code
 * BaseRefGit}/{@code TrustedBaseContext} in the call at all — there is no collaborator to instrument
 * because the manual-run classes never construct one. So the proof here is architectural: the
 * production source of the two manual-run drivers is scanned for any reference to the base-ref
 * network capability, mirroring {@link BaseHeadDefaultBoundarySpec}'s whole-tree reasoning.
 *
 * <p>This complements, rather than duplicates, {@code BaseRefLawBindingSpec}'s "manual run with no
 * --base still binds the clone's uncommitted working-tree edit" (task 5.7): that spec's fixture
 * clone has no {@code origin} remote configured at all, so it structurally cannot prove the
 * absence of a network call — there would be nothing to call regardless of what the production code
 * does. This spec proves the stronger claim: the manual-run code path cannot reach the network
 * layer even when a remote exists.
 */
class ManualRunOfflineBoundarySpec extends Specification {

    /** The two manual-run drivers (host and container) — the fresh-run and resume control flow. */
    private static final List<String> MANUAL_RUN_DRIVERS = [
        'application/src/main/java/com/github/oinsio/gnomish/app/GitModeRunner.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/ContainerGitModeRunner.java',
    ]

    /** The base-ref network capability and its trusted-tier context — fresh-claim-only inputs. */
    private static final List<String> NETWORK_BASE_REF_TYPES = [
        'BaseRefGit',
        'TrustedBaseContext',
        'BaseRefResolver',
    ]

    // FR4, FR8, UX2, UX3: no manual-run driver imports or names the network base-ref capability —
    //     if one ever did, a --base-less manual run could reach the remote, breaking the offline
    //     guarantee UX3 promises ("identical offline behavior" with or without --base).
    def "FR4, FR8, UX2, UX3: manual-run drivers never reference the base-ref network capability"() {
        given: 'the two manual-run drivers, comments stripped'
        def sources = RepoSourceTree.productionSources { path ->
            MANUAL_RUN_DRIVERS.contains(path)
        }

        expect: 'the scan really reached both files'
        sources.size() == MANUAL_RUN_DRIVERS.size()

        and: 'neither file names BaseRefGit, TrustedBaseContext or BaseRefResolver in code'
        sources.findAll { file ->
            def code = RepoSourceTree.code(file)
            NETWORK_BASE_REF_TYPES.any { code.contains(it) }
        }
        .collect { RepoSourceTree.relative(it) }
        .isEmpty()
    }
}
