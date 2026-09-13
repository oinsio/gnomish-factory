package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR11, M5, D12 of add-base-ref-resolution: the two whole-tree gates that keep the law source the
 * only way into {@code .gnomish/}, and the law commit the only thing the external-check pin
 * compares against.
 *
 * <p>Both are source-text scans for the same reason {@code RemotePrimitiveSingleSiteSpec} is one:
 * the subject is a string constant — a directory name, a git revision — that no bytecode-level
 * analysis can tell apart from any other string. And both are whole-tree questions, so they live
 * in {@code :bootstrap}, the one module that sees every layer at once (the {@code repoRoot} system
 * property comes from its own {@code verification.gradle}).
 *
 * <p>What they defend: before D12 the loader validated stage file references against the project's
 * {@code .gnomish/} root while the runtime resolved the same strings against the repository root,
 * so every fixture kept two copies of every law file and the two could disagree; and the pin guard
 * compared attempts against the literal {@code "HEAD"} of the shared factory clone, which a task
 * based on any other ref never matched. One root, one commit, one place each is spelled.
 */
class LawRootBoundarySpec extends Specification {

    /** The law-source and pin wiring: where a {@code "HEAD"} literal would re-introduce the old pin. */
    private static final List<String> LAW_AND_PIN_WIRING = [
        'adapters/src/main/java/com/github/oinsio/gnomish/adapter/law/',
        'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/PinCheckedExternalCheckClient.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/LawBinding.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/ManualRunLawBinding.java',
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/RunAssembler.java',
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/RunLaw.java',
    ]

    /**
     * The production sources allowed to spell the {@code .gnomish} directory name, and why:
     *
     * <ul>
     *   <li>{@code LawBinding} — the law root itself, the one owner of the rule (D12);</li>
     *   <li>{@code ObservabilityPaths}, {@code ManualRunConfiguration} — the operator's
     *       {@code ~/.gnomish} <em>home</em> directory (serve state, worktrees root), which is a
     *       different directory that happens to share a name and is no law root at all.</li>
     * </ul>
     */
    private static final List<String> LAW_ROOT_SPELLINGS = [
        'ManualRunConfiguration.java',
        'LawBinding.java',
        'ObservabilityPaths.java',
    ]

    // M5, D12: law and pin come from one commit. A "HEAD" literal here is the pre-D12 bug: it names
    //     whatever the shared factory clone is checked out at, which a task's base need not match.
    def "M5, D12: no HEAD literal survives in the law-source or pin wiring"() {
        given: 'the law-source and pin wiring, comments stripped — a javadoc may name the old pin'
        def sources = RepoSourceTree.productionSources { path ->
            LAW_AND_PIN_WIRING.any { path.startsWith(it) }
        }

        expect: 'the scan really reached those files'
        sources.size() >= LAW_AND_PIN_WIRING.size()

        and: 'none of them spells the revision; GitObjects.HEAD is named where the checkout is meant'
        sources.findAll { RepoSourceTree.code(it).contains('"HEAD"') }
        .collect { RepoSourceTree.relative(it) }
        .isEmpty()
    }

    // FR11, D12: one law root, reached one way. A second production site resolving `.gnomish` is how
    //     the load-vs-run divergence grew the first time, so a new one reds this.
    def "FR11, D12: only the law binding spells the law root"() {
        given: 'every production source naming the directory, comments stripped'
        def spellers = RepoSourceTree.productionSources()
                .findAll { RepoSourceTree.code(it) =~ /"\.gnomish(\/[^"]*)?"/ }
                .collect { it.name }
                .toSorted()

        expect:
        spellers == LAW_ROOT_SPELLINGS.toSorted()
    }
}
