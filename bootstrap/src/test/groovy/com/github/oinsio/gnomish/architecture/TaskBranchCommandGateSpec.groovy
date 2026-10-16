package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Design D5 of fix-terminal-receipt-convergence, the {@code TaskBranchAdoption} row: a branch a
 * clone holds only as a remote-tracking ref becomes local through the adoption owner and nowhere
 * else (FR7, FR8). Two older ways could make one local behind its back, and both are pinned here
 * (M6, implementation.md item 4):
 *
 * <ul>
 *   <li>{@code git branch <b> <start>} — it creates a head with no compare-and-swap. Its argv lives
 *       only in {@code TaskBranchCreator}, a fresh task's branch, which is not an adoption (D11);
 *   <li>{@code git worktree add <path> <b>} — when no local {@code <b>} exists git creates one from a
 *       same-named remote-tracking ref of its own accord. Its argv lives only in {@code
 *       TaskWorktreeManager}, which takes a {@code LocalTaskBranch} and verifies the local head
 *       before the add, so the fallback never engages (D10).
 * </ul>
 *
 * <p>The third feature closes the typed value's own hatch: a record's constructor is public, so a
 * {@code LocalTaskBranch} could be built from a bare task id anywhere. In production it is built
 * only where the owner answers — {@code TaskBranchAdoption}, and {@code ContainerResumeBranch}'s
 * reconcile of an already-local branch, the port entry that delegates every other location to it.
 *
 * <p>Each allowlist is exactly the set of files that match, so a survivor outside fails, and so does
 * an entry whose file no longer matches; every listed file is asserted reached. A text scan with
 * comments stripped, for the reason {@link BaseHeadDefaultBoundarySpec} gives.
 *
 * <p>M6, FR8 of fix-terminal-receipt-convergence.
 */
class TaskBranchCommandGateSpec extends Specification {

    private static final String GIT = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/'

    private static final Map<String, String> BRANCH_ARGV = [
        (GIT + 'TaskBranchCreator.java') : 'a fresh task\'s branch at its law commit, not an adoption (D11)',
    ]

    private static final Map<String, String> WORKTREE_ADD_ARGV = [
        (GIT + 'TaskWorktreeManager.java') : 'over an existing local branch only; the head is verified before the add (D10)',
    ]

    private static final Map<String, String> LOCAL_BRANCH_BUILDERS = [
        (GIT + 'TaskBranchAdoption.java') : 'the owner: adopted, or already local over the location it is handed',
        (GIT + 'ContainerResumeBranch.java') : 'the port entry\'s local arm: reconciled, then answered already local',
    ]

    /** The argv word {@code "branch"} followed by another argument. */
    static final Pattern BRANCH = ~/"branch",\s/

    /** The argv words {@code "worktree", "add"}. */
    static final Pattern WORKTREE_ADD = ~/"worktree",\s*"add"/

    /** A constructor call of either {@code LocalTaskBranch} record, qualified or imported. */
    static final Pattern LOCAL_BRANCH = ~/new\s+(BranchAdoption\s*\.\s*)?(AlreadyLocal|Adopted)\s*\(/

    private static List<File> sources() {
        RepoSourceTree.productionSources { path ->
            path.startsWith('adapters/') || path.startsWith('application/src/main/') ||
            path.startsWith('bootstrap/src/main/')
        }
    }

    private static List<String> matching(Pattern pattern) {
        sources().findAll { pattern.matcher(RepoSourceTree.code(it)).find() }
        .collect { RepoSourceTree.relative(it) }
        .sort()
    }

    // M6, FR8, design D5: each old way of making a branch local lives in its one listed file
    def "M6: #name appears only in its D5 allowlist, and every listed file is reached"() {
        expect: 'the scan reached every allowlisted file — a moved file must fail, not pass'
        allowlist.keySet().every { listed ->
            sources().any { RepoSourceTree.relative(it) == listed }
        }

        and: 'the files that match are exactly the allowlist: no survivor outside, no stale entry inside'
        matching(pattern) == allowlist.keySet().toSorted()

        where:
        name | pattern | allowlist
        'the git branch argv' | BRANCH | BRANCH_ARGV
        'the git worktree add argv' | WORKTREE_ADD | WORKTREE_ADD_ARGV
        'a LocalTaskBranch construction' | LOCAL_BRANCH | LOCAL_BRANCH_BUILDERS
    }

    // The detectors are the gate: each shape must be found, and its near neighbours must not be.
    def "the detectors: #shape"() {
        expect:
        pattern.matcher(RepoSourceTree.stripComments(source)).find() == detected

        where:
        shape | pattern | source || detected
        'a branch create' | BRANCH | 'runner.run(dir, "branch", name, base);' || true
        'a branch delete' | BRANCH | 'runner.run(dir, "branch", "-D", name);' || true
        'a branch word in a message' | BRANCH | 'log.info("branch {} created", name);' || false
        'a javadoc mention of the argv' | BRANCH | '/** runs "branch", name */\nint x;' || false
        'a worktree add' | WORKTREE_ADD | 'runner.run(dir, "worktree", "add", path, name);' || true
        'a worktree list' | WORKTREE_ADD | 'runner.run(dir, "worktree", "list", "--porcelain");' || false
        'a qualified adopted construction' | LOCAL_BRANCH | 'return new BranchAdoption.Adopted(id);' || true
        'an imported already-local one' | LOCAL_BRANCH | 'return new AlreadyLocal(id);' || true
        'a nowhere answer' | LOCAL_BRANCH | 'return new BranchAdoption.Nowhere();' || false
        'a pattern match on the answer' | LOCAL_BRANCH | 'case BranchAdoption.AlreadyLocal(String id) -> id;' || false
    }
}
