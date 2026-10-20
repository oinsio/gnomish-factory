package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D13 as amended 2026-10-13, D18; single-owner rows
 * "The factory register" and "The harvest admission"): the register moves only with a factory
 * closing commit, in one ref transaction with the branch, and reaches origin only in one atomic push
 * with it; the task branch moves from box content only through the admission. The types keep the
 * writers on their owners, and this scan keeps the set of files that can reach each primitive, or
 * name the register or the quarantine, exactly what it is today:
 *
 * <ul>
 *   <li>no production source outside {@code :gittransfer} spells a push argv — the push is built by
 *       the transfer owner's {@code GitPush}, as every fetch and clone is by {@code GitTransfer};
 *   <li>the atomic closing push is built in {@code ClosingPush} alone, the branch-only push in
 *       {@code RefspecPush} alone, and the branch-only primitive is held by the two mid-round push
 *       points alone ({@code BestEffortPush#midRound}, {@code HarvestAdmission#pushAdmitted});
 *   <li>{@code ClosingPush} is held by exactly the closing writers and catch-up pushes listed below —
 *       a new push point is added to this list in the change that adds it, never silently;
 *   <li>every move of the task branch and its register in the clone is one ref transaction built
 *       in {@code FactoryRegister} alone ({@code RefTransaction}, whose argv is the one spelling of
 *       {@code update-ref} outside the bare-object writer), held by exactly four callers: the
 *       host closing commit ({@code WorktreeClosingCommit}), the harvest admission, the replica
 *       reconciler's adoption of origin's line, and the branch adoption ({@code
 *       TaskBranchAdoption}, design D9 of fix-terminal-receipt-convergence);
 *   <li>no production source runs a factory-side {@code git commit}: a closing commit is made as an
 *       object first ({@code commit-tree}, in {@code WorktreeClosingCommit} alone, or the
 *       bare-object writer), so it can land with its register;
 *   <li>the harvest fetch is built by the transfer owner's {@code GitTransfer.harvest} alone, called
 *       by {@code ContainerHarvestFetch} alone, and its destination is a quarantine named by
 *       {@code FactoryRegisterRefs} alone — so no harvest names the task branch;
 *   <li>the register's namespace is spelled in {@code RegisterRef} alone, and a register value is
 *       made from a task id in {@code FactoryRegisterRefs} alone (the naming half split from
 *       {@code FactoryRegister} for the file-size cap, which keeps the transactions);
 *   <li>the ref backend those transactions are atomic on is spelled, and read, in
 *       {@code FactoryCloneRefFormat} alone, reached through
 *       {@code GitTaskBranches#requireRefFormat} and {@code TouchpointFetch} (the first write of every task touchpoint, task 9.13g); the
 *       factory creates no clone, so {@code --ref-format} is spelled only in the refusal's remedy.
 * </ul>
 *
 * <p>Each row asserts the file set exactly, so a moved or renamed allowlisted file fails rather
 * than widening the gate; every source is read with comments stripped, so prose never counts.
 */
class FactoryRegisterOwnerSpec extends Specification {

    private static final String GIT = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/'

    private static final String TRANSFER_OWNER = 'gittransfer/src/main/'

    /**
     * The test fixture standing in for the container harvest over a local path (design D18): {@code
     * test-fixtures/src/main} is a production source set to this scan, so it is listed rather than
     * exempted by tree. It takes the refspec from the owner, as the production fetch does.
     */
    private static final String LOCAL_BOX_FIXTURE =
    'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/adapter/git/LocalBoxEnvironment.groovy'

    /** The bare-object writer: its own module's ref transaction, the second spelling of update-ref. */
    private static final String OBJECTS_WRITER = 'gitobjects/src/main/java/com/github/oinsio/gnomish/gitobjects/CommitBuilder.java'

    /** A factory-side commit argv: {@code "commit"} as an argument of a runner call. */
    private static final Pattern COMMIT_ARGV = ~/\brun\s*\([^;]*"commit"/

    /** A push argv: {@code "push"} as an argument of a call that builds or runs one. */
    private static final Pattern PUSH_ARGV = ~/\b(run|of|add|asList)\s*\([^;]*"push"/

    // FR13: the file set that holds each token, outside the transfer owner — exactly.
    def "FR13: '#token' appears in exactly its owners"() {
        expect:
        holders(token) == owners.collect {
            it.contains('/src/') ? it : GIT + it
        }.toSet()

        where:
        token | owners
        'GitPush.closing(' | ['ClosingPush.java']
        'GitPush.branchOnly(' | ['RefspecPush.java']
        'new RefspecPush(' | [
            'BestEffortPush.java',
            'HarvestAdmission.java'
        ]
        'new ClosingPush(' | [
            'BestEffortClosingPush.java',
            'BestEffortPush.java',
            'FirstPush.java',
            'LifecyclePush.java',
            'OriginReconciliation.java',
            'ParkDeliveryFence.java',
            'RemoteAttemptDelivery.java',
        ]
        'new FactoryRegister(' | [
            'HarvestAdmission.java',
            'ReplicaPairReconciler.java',
            'TaskBranchAdoption.java',
            'WorktreeClosingCommit.java',
        ]
        'new RefTransaction(' | ['FactoryRegister.java']
        '"update-ref"' | [
            'RefTransaction.java',
            OBJECTS_WRITER
        ]
        '"commit-tree"' | [
            'WorktreeClosingCommit.java',
            OBJECTS_WRITER
        ]
        'RegisterRef.forTask(' | ['FactoryRegisterRefs.java']
        'new RegisterRef(' | []
        'gnomish-factory/' | []
        'QuarantineRef.forTask(' | ['FactoryRegisterRefs.java']
        'new QuarantineRef(' | []
        'gnomish-quarantine/' | []
        'GitTransfer.harvest(' | ['ContainerHarvestFetch.java']
        '.harvestRefspec(' | [LOCAL_BOX_FIXTURE]
        '"reftable"' | ['FactoryCloneRefFormat.java']
        '"--show-ref-format"' | ['FactoryCloneRefFormat.java']
        'new FactoryCloneRefFormat(' | [
            'GitTaskBranches.java',
            'TouchpointFetch.java'
        ]
        '--ref-format' | [
            'application/src/main/java/com/github/oinsio/gnomish/app/port/git/CloneRefFormatRefusedException.java'
        ]
    }

    // FR13 (design D13 as amended 2026-10-13): no factory-side `git commit` — every closing commit is
    //     made as an object, so it lands with its register in one ref transaction.
    def "FR13: no production source runs a factory-side git commit"() {
        given:
        def sources = RepoSourceTree.productionSources()

        expect:
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.findAll {
            RepoSourceTree.code(it).readLines().any {
                COMMIT_ARGV.matcher(it).find()
            }
        }.collect { RepoSourceTree.relative(it) } == []
    }

    def "the commit-argv detector finds a seeded argv and leaves an in-box script alone: #shape"() {
        expect:
        RepoSourceTree.stripComments(source).readLines().any {
            COMMIT_ARGV.matcher(it).find()
        } == detected

        where:
        shape | source || detected
        'a runner argv' | 'runner.run(worktree, "commit", "-m", message);' || true
        'an in-box script' | 'String SCRIPT = "git add -A && git -c core.hooksPath= commit -m \\"$1\\"";' || false
        'a commit-tree argv' | 'runner.run(worktree, "commit-tree", tree, "-p", parent);' || false
    }

    // FR13: the push argv has one builder, as every transfer does (ADR 0008).
    def "FR13: no production source outside the transfer owner spells a push argv"() {
        given:
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan reached the tree, and the owner itself builds one'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.any {
            RepoSourceTree.relative(it).startsWith(TRANSFER_OWNER) && spellsPushArgv(RepoSourceTree.code(it))
        }

        and: 'nothing outside it does'
        sources.findAll {
            !RepoSourceTree.relative(it).startsWith(TRANSFER_OWNER) && spellsPushArgv(RepoSourceTree.code(it))
        }.collect { RepoSourceTree.relative(it) } == []
    }

    // Over a clean tree the detector never fires, so only seeded lines tell it from a broken one.
    def "the push-argv detector finds a seeded argv and leaves a label or a comment alone: #shape"() {
        expect:
        spellsPushArgv(RepoSourceTree.stripComments(source)) == detected

        where:
        shape | source || detected
        'a runner argv' | 'runner.run(repo, "push", OriginRemote.NAME, refspec);' || true
        'a built list' | 'return List.of("push", "--atomic", "origin");' || true
        'an appended element' | 'argv.add("push");' || true
        'an outcome label' | 'String outcome = PushOutcome.describe("push", result);' || false
        'a classifier arm' | 'case "fetch", "push" -> true;' || false
        'a comment' | '// runner.run(repo, "push", "origin")' || false
    }

    /** Production files outside the transfer owner whose code holds {@code token}. */
    private static Set<String> holders(String token) {
        RepoSourceTree.productionSources()
                .findAll {
                    !RepoSourceTree.relative(it).startsWith(TRANSFER_OWNER)
                }
                .findAll { RepoSourceTree.code(it).contains(token) }
                .collect { RepoSourceTree.relative(it) }
                .toSet()
    }

    private static boolean spellsPushArgv(String code) {
        code.readLines().any { PUSH_ARGV.matcher(it).find() }
    }
}
