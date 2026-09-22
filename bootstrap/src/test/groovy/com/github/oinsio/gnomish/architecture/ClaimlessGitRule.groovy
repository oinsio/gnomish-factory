package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree

/**
 * The claimless-assembly rule itself (FR5, M3 of fix-claim-epoch-fence), held apart from the gate
 * that runs it: the three tokens it judges a source by, and the scan that decides whether a source
 * spells one.
 *
 * <p>{@link ClaimlessGitBoundarySpec} owns the trees, the allowlists and the whole-tree
 * assertions; {@link ClaimlessGitDetectorSpec} drives this class over seeded sources. Splitting it
 * out is what gives the detector teeth: over a clean tree every rule finds nothing, so a scan that
 * stopped matching would look exactly like a codebase that stopped offending.
 */
final class ClaimlessGitRule {

    /** A git layer wired to no tenure: the token rule 1 and rule 2 place. */
    static final String CLAIMLESS_SOURCE = 'ClaimEpochSource.NONE'

    /** A freshly minted tenure record: the token that says "this file owns a book". */
    static final String FRESH_BOOK = 'new ClaimEpochBook()'

    /**
     * Building a bundle through the fixture — the construction that already carries a book of its
     * own, so a {@link #FRESH_BOOK} beside it in the same file is a SECOND record.
     *
     * <p>A hand-built {@code new TaskGit(...)} is deliberately not listed: since task 5.1 the book
     * is one of the record's components, so such a call has to be handed one, and a port-fake spec
     * spells it inline — that mint IS the bundle's record, not a rival to it.
     */
    static final String BUNDLE_CONSTRUCTION = 'TaskGitFixture.real('

    private ClaimlessGitRule() {
    }

    /** Whether this source spells the token in code — a javadoc mention of it is never a hit. */
    static boolean spells(File file, String token) {
        RepoSourceTree.code(file).contains(token)
    }

    /** Every non-allowlisted source that spells the token, by path — the gate's failure list. */
    static List<String> offenders(List<File> sources, String token, List<String> allowlist) {
        sources.findAll { spells(it, token) }
        .collect { RepoSourceTree.relative(it) }
        .findAll { !allowlist.contains(it) }
        .sort()
    }

    /**
     * The allowlisted paths that really spell the token they are exempted for. Equality against
     * the allowlist is what the gate asserts, so an exemption fails loudly on both halves of going
     * stale: the file was renamed or moved (it is no longer among the sources), or it stopped
     * needing the exemption (it no longer spells the token) and may now reintroduce it unwatched.
     */
    static Set<String> allowlistedSpelling(List<File> sources, List<String> allowlist, String token) {
        sources.findAll {
            allowlist.contains(RepoSourceTree.relative(it)) && spells(it, token)
        }
        .collect { RepoSourceTree.relative(it) }
        .toSet()
    }
}
