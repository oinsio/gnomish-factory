package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR8, FR9, M2 of fix-envelope-medium: the envelope is read from the tip, and its path names have
 * one spelling.
 *
 * <p>The defect this gate exists for had a green build. The host adapter read {@code
 * .gnomish-task/task.json} straight from the worktree, so a predecessor killed between {@code git
 * rm} and its commit left a working copy whose files disagreed with the commit every other reader
 * classified; each component was correct in isolation. A convention ("read the tip") cannot be
 * checked, and the read takes a {@code Path} a caller can always resolve a file under, so the type
 * cannot enforce it either (design D6). The scan is the enforcement, in {@code :bootstrap} — the
 * one module that sees every layer at once, the same shape and placement {@link
 * ClaimlessGitBoundarySpec} and {@link BaseHeadDefaultBoundarySpec} use.
 *
 * <p>Two rules:
 *
 * <ul>
 *   <li><b>Rule 1, the read medium.</b> In {@code adapters/git/src/main} and the application's
 *       {@code app} package, a filesystem read call is allowlisted by path with its reason or it is
 *       a defect: every envelope read belongs to {@code GitTaskStore} over {@code GitShowTip} at
 *       the worktree's {@code HEAD}. The sibling {@code dashboard} and {@code serveobservability}
 *       packages are outside the scanned tree on purpose — they read their own ledger and snapshot
 *       files, never an envelope.
 *   <li><b>Rule 2, the path names.</b> No production source outside {@code EnvelopePaths} spells
 *       the directory or either file name (design D8).
 * </ul>
 *
 * <p>Both rules read sources with comments stripped ({@link RepoSourceTree#codeOnly}), what the
 * compiler sees, so a javadoc mention of {@code "task.json"} is never a hit. The allowlist is
 * paths, not patterns, and rule 1 asserts the scan really reached every file it allows: a renamed
 * or moved exemption fails loudly instead of widening the gate silently.
 *
 * <p>The tokens, the scan and the failure text live in {@link EnvelopeMediumRule}; this file owns
 * the owned trees, the allowlists and the whole-tree assertions. {@link
 * EnvelopeMediumDetectorSpec} drives the rule over seeded violating lines, which is the only way
 * the UX2 failure text is exercised — over the production tree both rules find nothing.
 */
class EnvelopeMediumBoundarySpec extends Specification {

    /** The two trees rule 1 governs. */
    private static final List<String> OWNED_TREES = [
        'adapters/git/src/main/',
        'application/src/main/java/com/github/oinsio/gnomish/app/',
    ]

    /**
     * The files that legitimately read a path that is not an envelope, each with the read it makes:
     *
     * <ul>
     *   <li>{@code TaskWorktreeManager} — {@code isDirectory} on the worktree path before
     *       registration;
     *   <li>{@code DirectoryWorkspace} — {@code isDirectory} on the workspace root;
     *   <li>{@code WorktreeJanitor} — {@code isDirectory}/{@code list}/{@code walk} over workspace
     *       roots;
     *   <li>{@code AdHocTaskSynthesizer} — {@code readString} of an operator's task file.
     * </ul>
     */
    private static final List<String> NON_ENVELOPE_READERS = [
        'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/TaskWorktreeManager.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/AdHocTaskSynthesizer.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/serve/WorktreeJanitor.java',
        'application/src/main/java/com/github/oinsio/gnomish/app/workspace/DirectoryWorkspace.java',
    ]

    /** The one spelling of the envelope's path names, and the only file rule 2 allows to hold them. */
    private static final String PATH_OWNER =
    'domain/src/main/java/com/github/oinsio/gnomish/domain/branch/EnvelopePaths.java'

    // FR8, M2: a working copy is the staging area for the next commit, not a read medium — a
    //     filesystem read in these trees is either one of the four non-envelope reads named above
    //     or the defect this change removed.
    def "FR8, M2: only the allowlisted non-envelope readers touch the filesystem in the git adapter and the app package"() {
        given: 'every production source of the owned trees'
        def sources = ownedSources()

        and: 'the allowlisted files the scan reached, and the read calls outside the allowlist'
        def reached = allowlistedAmong(sources)
        def offenders = readCallsOutsideAllowlist(sources)

        expect: 'the scan really reached every allowlisted file'
        reached == NON_ENVELOPE_READERS.toSet()

        and: 'no other source in these trees reads the filesystem'
        assert offenders.isEmpty(): EnvelopeMediumRule.readViolation(offenders)
    }

    // FR9, M6: three modules spelled these names by hand before this change, and nothing failed
    //     when one of them drifted; the owner is only an owner while no one retypes it.
    def "FR9, M6: no production source outside EnvelopePaths spells the envelope's path names"() {
        given: 'every production source of the build except the owner'
        def sources = RepoSourceTree.productionSources { path ->
            path != PATH_OWNER
        }

        and: 'the owner is itself among the sources the scan walks'
        def ownerReached = RepoSourceTree.productionSources().collect {
            RepoSourceTree.relative(it)
        }.contains(PATH_OWNER)

        and: 'the literals spelled outside the owner'
        def offenders = literalsOutsideOwner(sources)

        expect: 'the scan reaches the owner, so the exemption is a real path'
        ownerReached

        and: 'and nothing else spells them'
        assert offenders.isEmpty(): EnvelopeMediumRule.pathViolation(offenders)
    }

    /** Every production source of the two trees rule 1 governs. */
    private static List<File> ownedSources() {
        RepoSourceTree.productionSources { path ->
            OWNED_TREES.any {
                path.startsWith(it)
            }
        }
    }

    /** The allowlisted paths that really hold one of the ten calls, so a moved exemption fails loudly. */
    private static Set<String> allowlistedAmong(List<File> sources) {
        sources.findAll { file ->
            NON_ENVELOPE_READERS.contains(RepoSourceTree.relative(file)) && !EnvelopeMediumRule.hits(file, EnvelopeMediumRule.READ_CALLS).isEmpty()
        }
        .collect { RepoSourceTree.relative(it) }
        .toSet()
    }

    /** Every filesystem read call in a non-allowlisted file of the owned trees, as {@code path:line — call}. */
    private static List<String> readCallsOutsideAllowlist(List<File> sources) {
        sources.findAll {
            !NON_ENVELOPE_READERS.contains(RepoSourceTree.relative(it))
        }
        .collectMany {
            EnvelopeMediumRule.hits(it, EnvelopeMediumRule.READ_CALLS)
        }
        .sort()
    }

    /** Every envelope path literal in a production source other than the owner. */
    private static List<String> literalsOutsideOwner(List<File> sources) {
        sources.collectMany {
            EnvelopeMediumRule.hits(it, EnvelopeMediumRule.PATH_LITERALS)
        }.sort()
    }
}
