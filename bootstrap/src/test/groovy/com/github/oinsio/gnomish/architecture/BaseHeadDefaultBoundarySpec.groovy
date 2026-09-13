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

    /**
     * The autonomous-tier base wiring: the two files task 6.1 emptied of their hand-rolled
     * {@code null -> "HEAD"} default, plus every file the autonomous fresh-claim base decision
     * now flows through. A {@code "HEAD"} spelled anywhere along this path — as a default, or as
     * a stand-in {@code TrustedBaseContext#defaultBranch()} — reaches {@code BaseRefResolver} in
     * {@code AUTONOMOUS} mode as the repository default branch, which resolves it and reports it
     * as "the repository default branch reported by the remote": a base nobody chose.
     *
     * <p>The list covers the value's <em>consumers</em> only, and deliberately so. Its producers —
     * {@code TrustedTierStartup}, which binds the branch from origin, and {@code ServeCommand}/{@code
     * TakeCommand}, the only two sites that construct a {@code TrustedBaseContext} — were briefly
     * listed here too, until the 2026-09-12 review asked what a hand-maintained file list is worth
     * against a defect whose whole shape is "the list did not keep up". Nothing: a new producer is
     * outside the list by default, and the gate stays green while it hands the resolver a stand-in.
     * So the trusted tier's half is enforced by {@code DefaultBranch} instead — a stand-in there no
     * longer compiles, and a name the remote never reported no longer constructs
     * ({@code DefaultBranchSpec}, and {@code RemoteDefaultBranchSpec}'s HEAD-named remote on real
     * git). What remains below is the half no type covers: these files handle a resolved base as a
     * plain ref name, which is what it is, so only a text scan can say they do not invent one.
     */
    private static final List<String> BASE_DEFAULT_WIRING = [
        'application/src/main/java/com/github/oinsio/gnomish/app/GitFreshTaskSupport.java',
        'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/TaskBranchCreator.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/TakeFreshClaim.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/TakeContainerFreshClaim.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/TakeBareAuto.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/TakeDisposition.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/FreshClaimBaseBinding.java',
    ]

    // FR4, FR10, M2: a "HEAD" literal reappearing anywhere on this path is the pre-6.1 bug
    //     returning — one of the two duplicated defaults this task deleted, or a stand-in trusted
    //     tier handing the autonomous resolver the local HEAD under the default-branch tier's name.
    //     The second of those is now unconstructible rather than merely unscanned; see the javadoc.
    def "FR4, FR10, M2: no HEAD literal survives in the autonomous-tier base wiring"() {
        given: 'the autonomous-tier base wiring, comments stripped — a javadoc may still name the old default'
        def sources = RepoSourceTree.productionSources { path ->
            BASE_DEFAULT_WIRING.contains(path)
        }

        expect: 'the scan really reached every listed file'
        sources.size() == BASE_DEFAULT_WIRING.size()

        and: 'no file on the path spells the literal in code'
        sources.findAll { RepoSourceTree.code(it).contains('"HEAD"') }
        .collect { RepoSourceTree.relative(it) }
        .isEmpty()
    }
}
