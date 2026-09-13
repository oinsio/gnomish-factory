package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR15, NFR-S1, D12 of add-base-ref-resolution (revised 2026-09-10): once the refresh has read a
 * commit back from its destination ref, that commit is the only start point — a base <em>name</em>
 * never crosses a port again except as pin metadata. The rule is recorded in
 * {@code docs/adr/0006-base-refresh-fetch.md}; this is its gate.
 *
 * <p>What it guards against, concretely: git's bare-name lookup order (gitrevisions —
 * {@code $GIT_DIR/<n>}, {@code refs/<n>}, {@code refs/tags/<n>}, {@code refs/heads/<n>},
 * {@code refs/remotes/<n>}) never reaches {@code refs/remotes/origin/<n>}, which is where the
 * branch refresh lands. On 2026-09-10 a stale local {@code main}, an origin-only branch and a
 * planted local tag each won a {@code rev-parse} that ran after a successful fetch — three
 * different wrong answers from one re-resolution.
 *
 * <p>Two questions, because the defect has two halves: the call is gone from the components that
 * made it, and no base name exists in the module for a future component to make it with. A blanket
 * ban on {@code rev-parse} would be the wrong instrument — the git adapter legitimately resolves
 * task-branch tips, {@code HEAD}s and attempt commits all over — so the scan is anchored on the
 * base name instead.
 */
class BaseNameResolutionBoundarySpec extends Specification {

    /** The three components the revision took the base-name resolution out of. */
    private static final List<String> START_POINT_CONSUMERS = [
        'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/TaskBranchCreator.java',
        'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/GitTaskRepository.java',
        'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/GitObjectsTaskRepository.java',
    ]

    // FR15: the branch creator and both lifecycle stores take an already-peeled commit, so the
    //     rev-parse each of them used to run on a base name has nothing left to resolve.
    def "FR15: the start-point consumers run no rev-parse at all"() {
        given:
        def sources = RepoSourceTree.productionSources { path ->
            START_POINT_CONSUMERS.contains(path)
        }

        expect: 'the scan really reached all three'
        sources.size() == START_POINT_CONSUMERS.size()

        and:
        sources.findAll { RepoSourceTree.code(it).contains('"rev-parse"') }
        .collect { RepoSourceTree.relative(it) }
        .isEmpty()
    }

    // FR15, NFR-S1: outside the pin's own serialization, no base NAME value exists in the git
    //     adapter at all — the fetch-side readers (BaseRefresh, RefreshedTip, TagBaseFetch,
    //     CommitBaseFetch, ResumeBaseResolution) speak of a `ref` they are fetching, and everything
    //     downstream of them holds a commit. A `baseRef` reappearing here is a name travelling past
    //     the refresh, which is the shape of the defect however it is later resolved.
    def "FR15: no base-name value survives in adapters/git outside the pin's serialization"() {
        given:
        def sources = RepoSourceTree.productionSources { path ->
            path.startsWith('adapters/git/src/main/') && !path.contains('/adapter/git/state/')
        }

        expect: 'the scan really reached the module'
        sources.size() > START_POINT_CONSUMERS.size()

        and:
        sources.findAll { RepoSourceTree.code(it).contains('baseRef') }
        .collect { RepoSourceTree.relative(it) }
        .isEmpty()
    }
}
