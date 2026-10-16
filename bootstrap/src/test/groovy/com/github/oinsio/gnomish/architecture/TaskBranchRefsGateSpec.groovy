package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Design D5 of fix-terminal-receipt-convergence, the {@code TaskBranchRefs} row: a task branch's
 * two full ref names — its head {@code refs/heads/gnomish/<b>} and its remote-tracking ref {@code
 * refs/remotes/origin/gnomish/<b>} — are spelled in one place, {@code TaskBranchRefs} in {@code
 * adapters/git}. The refs are {@code String}s because the git argv needs them, so no type can keep
 * the spelling single-owner; this gate does (M7, implementation.md item 4).
 *
 * <p>The ban is on the two prefixes inside a string literal, not on the {@code gnomish/} branch
 * namespace: a task ref is built as {@code "refs/heads/" + branch}, and the branch name is a
 * variable the scan cannot see. So the prefix itself is the old way, and the readers that spell it
 * for a ref that is not a task branch — the task's <b>base</b>, origin's default branch — are
 * listed with their reasons. The list is exactly the set of files that spell a prefix, so a
 * survivor outside it fails, and so does an entry whose file no longer spells one; every listed
 * file is asserted reached. The {@code gittransfer} leaf's push and register argv spell their own,
 * outside the three scanned trees (design D5).
 *
 * <p>A text scan in {@code :bootstrap}, for the reason {@link BaseHeadDefaultBoundarySpec} gives,
 * comments stripped: a javadoc may cite the ref it no longer builds.
 *
 * <p>M7, FR8 of fix-terminal-receipt-convergence.
 */
class TaskBranchRefsGateSpec extends Specification {

    private static final String GIT = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/'
    private static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/app/'

    /** Every file that may spell a branch-ref prefix in a string literal, each with why. */
    private static final Map<String, String> PREFIX_SPELLERS = [
        (GIT + 'TaskBranchRefs.java') : 'the owner: the one spelling of a task branch\'s head and tracking ref',
        (GIT + 'RemoteBaseRef.java') : 'the base ref, not a task branch: asks origin for refs/heads/<base> beside refs/tags/<base>',
        (GIT + 'BaseRefresh.java') : 'the base ref, not a task branch: fetches refs/heads/<base> and names both namespaces in its refusal',
        (GIT + 'RemoteDefaultBranch.java') : 'origin\'s default branch, not a task branch: parses ls-remote --symref\'s "ref: refs/heads/"',
        (APP + 'ManualResumeLawBinding.java') : 'the base ref, not a task branch: binds a manual resume\'s law to refs/heads/<pinned base>',
    ]

    /** A string literal holding either prefix, wherever in the literal it starts. */
    private static final Pattern PREFIX_LITERAL = ~/"[^"\n]*refs\/(heads|remotes\/origin)\//

    // M7, FR8, design D5: one owner of the task-branch ref spelling; the base-ref readers are listed
    def "M7: a branch-ref prefix literal appears in the three layers only in the D5 allowlist"() {
        given: 'every production source of adapters/git, application and bootstrap, comments stripped'
        def sources = RepoSourceTree.productionSources { path ->
            path.startsWith('adapters/git/src/main/') || path.startsWith('application/src/main/') ||
            path.startsWith('bootstrap/src/main/')
        }

        expect: 'the scan reached every allowlisted file — a moved file must fail, not pass'
        PREFIX_SPELLERS.keySet().every { listed ->
            sources.any { RepoSourceTree.relative(it) == listed }
        }

        and: 'the files that spell a prefix are exactly the allowlist: no survivor outside, no stale entry inside'
        sources.findAll {
            PREFIX_LITERAL.matcher(RepoSourceTree.code(it)).find()
        }
        .collect { RepoSourceTree.relative(it) }
        .sort() == PREFIX_SPELLERS.keySet().toSorted()
    }

    // The detector is the gate: each prefix in every literal shape must be found, and the register
    //     and tag namespaces, the owner's call and a javadoc mention must not be.
    def "the prefix detector: #shape"() {
        expect:
        PREFIX_LITERAL.matcher(RepoSourceTree.stripComments(source)).find() == detected

        where:
        shape | source || detected
        'a head built inline' | 'String ref = "refs/heads/" + branch;' || true
        'a tracking ref built inline' | 'String ref = "refs/remotes/origin/" + branch;' || true
        'a head namespace constant' | 'static final String P = "refs/heads/gnomish/";' || true
        'a forced refspec' | 'new Refspec("+refs/heads/" + name + ":" + tracking);' || true
        'a prefix inside a message' | 'return "neither refs/heads/" + ref + " nor the tag";' || true
        'the owner\'s call' | 'String ref = TaskBranchRefs.head(taskId);' || false
        'the register namespace' | 'String ref = "refs/gnomish-factory/" + core;' || false
        'the tag namespace' | 'String ref = "refs/tags/" + name;' || false
        'another remote' | 'String ref = "refs/remotes/upstream/" + name;' || false
        'a javadoc mention' | '/** Builds refs/heads/gnomish/<b> as "refs/heads/" + b once. */\nint x;' || false
    }
}
