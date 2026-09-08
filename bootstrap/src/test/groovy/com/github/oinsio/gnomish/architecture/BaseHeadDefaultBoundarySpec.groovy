package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR4, FR10, M2 of add-base-ref-resolution: the two duplicated ad-hoc {@code null -> "HEAD"}
 * defaults on the fresh-run path are gone, replaced by the one real policy component,
 * {@code BaseRefResolver} (module {@code :baseref}). This is a whole-tree question — a literal
 * could reappear in either file at any layer — so it lives in {@code :bootstrap}, the one module
 * that sees every layer at once, the same reasoning {@link LawRootBoundarySpec} already uses for
 * its own {@code "HEAD"} check.
 *
 * <p>This does not ban {@code "HEAD"} literals across {@code :application}/{@code :adapters}
 * wholesale: the git layer legitimately names the real ref {@code HEAD} elsewhere (resolving the
 * worktree's current commit, probing a remote's default branch, an HTTP method constant) — none of
 * those are the base-default decision this change moved into {@code BaseRefResolver}. What must
 * stay clean is the two specific files that used to make that decision by hand.
 */
class BaseHeadDefaultBoundarySpec extends Specification {

    /** The two files task 6.1 emptied of their hand-rolled {@code null -> "HEAD"} default. */
    private static final List<String> BASE_DEFAULT_WIRING = [
        'application/src/main/java/com/github/oinsio/gnomish/app/GitFreshTaskSupport.java',
        'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/TaskBranchCreator.java',
    ]

    // FR4, FR10, M2: a "HEAD" literal reappearing in either file is the pre-6.1 bug returning —
    //     one of the two duplicated defaults this task deleted.
    def "FR4, FR10, M2: no HEAD literal survives in the fresh-task base-default wiring"() {
        given: 'the base-default wiring, comments stripped — a javadoc may still name the old default'
        def sources = RepoSourceTree.productionSources { path ->
            BASE_DEFAULT_WIRING.contains(path)
        }

        expect: 'the scan really reached both files'
        sources.size() == BASE_DEFAULT_WIRING.size()

        and: 'neither file spells the literal in code'
        sources.findAll { RepoSourceTree.code(it).contains('"HEAD"') }
        .collect { RepoSourceTree.relative(it) }
        .isEmpty()
    }
}
