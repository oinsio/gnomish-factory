package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * F122, F135, FR13, NFR-S2 of tighten-spec-contract (design D13, single-owner row "The factory
 * register"): every reader of task-branch state takes the envelope from the task's registered
 * commit, never from the branch tip or {@code HEAD}. The type keeps the readers honest — {@code
 * GitShowTip} takes a {@code RegisteredCommit}, and only {@code FactoryRegisterRead} makes one —
 * and this scan keeps the set of files that touch an envelope file at all exactly what it is today,
 * each with the reason it may:
 *
 * <ul>
 *   <li><b>register readers</b> read through {@code GitShowTip} or a {@code BranchTipSource}, at a
 *       registered commit;
 *   <li><b>writers</b> write the envelope into the commit they are about to make;
 *   <li><b>writer bases</b> read the file the write they serve rewrites — at the registered commit
 *       ({@code RegisterBase}, design D18) or the clone's branch tip (the container writer, whose
 *       branch carries the register's envelope: only the admission moves it above the register,
 *       and it refuses an owned path) — and decide no pickup;
 *   <li><b>the admission rule</b> names the files the factory's own in-box state commit may change.
 * </ul>
 *
 * <p>The scan covers {@code adapters/git/src/main} and {@code application/src/main}, with comments
 * stripped ({@link RepoSourceTree#code}); every allowlisted file is asserted reached, so a moved or
 * renamed file fails instead of widening the gate. The shape is {@link EnvelopeMediumBoundarySpec}'s.
 */
class EnvelopeRegisterOwnerSpec extends Specification {

    private static final String GIT = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/'

    private static final List<String> OWNED_TREES = [
        'adapters/git/src/main/',
        'application/src/main/'
    ]

    /** What names an envelope file in code: the two path constants and their aliases. */
    private static final List<String> ENVELOPE_TOKENS = [
        'TASK_JSON_PATH',
        'STATE_JSON_PATH'
    ]

    /** Every file of the owned trees that may name an envelope file, with the reason it may. */
    private static final Map<String, String> ENVELOPE_HOLDERS = [
        'BranchTipFactsReader.java': 'register reader: reads through the source at the registered commit',
        'TipEnvelopeReader.java': 'register reader: reads through the source at the registered commit',
        'DeliveredBranchReader.java': 'register reader: the registered commit, or the Completed commit under a registered cleanup',
        'GitTaskStore.java': 'register reader: the registered commit of the worktree\'s own task branch',
        'UsageHistoryWalker.java': 'register reader: walks state.json history down from the registered commit',
        'GitAttemptPersistence.java': 'writer: the round commit\'s state.json',
        'EnvironmentAttemptPersistence.java': 'writer: the in-box state commit\'s state.json',
        'GitTaskRepository.java': 'writer: the lifecycle commit\'s task.json',
        'TerminalWriteMarker.java': 'writer: the receipt\'s task.json rewrite',
        'StateFileWrite.java': 'writer, and its cursor carry-forward base at the register (RegisterBase)',
        'RequiredTaskJson.java': 'writer base: task.json at the register for the lifecycle rewrite (RegisterBase)',
        'RequiredTaskState.java': 'writer base: state.json at the register for the lifecycle rewrite (RegisterBase)',
        'AdmissionRule.java': 'admission rule: the files the factory\'s own in-box state commit may change',
        'TaskLifecycleCommitWriter.java': 'writer and writer base of the container medium, at the clone\'s branch',
        'GitObjectsTerminalCommits.java': 'writer guard of the container medium: is the envelope still there to clear',
        'HarvestedStateCommitCheck.java': 'writer self-check: the harvested state commit carries what was written',
    ].collectEntries { k, v -> [(GIT + k): v] }

    // FR13: the envelope is read at the registered commit — a new reader of an envelope file in
    //     either tree fails here until it is added with its reason, which it can only honestly be as
    //     a register reader.
    // Scenario: lifecycle/task-branch-contract / No reader takes an envelope fact from the tip
    def "FR13: only the allowlisted register readers, writers and writer bases name an envelope file"() {
        given:
        def holders = ownedSources().findAll {
            holdsEnvelope(RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) }.toSet()

        expect: 'the scan reached every allowlisted file, and found no other'
        holders == ENVELOPE_HOLDERS.keySet()
    }

    // FR13: the escape hatch is the type — a registered commit has one producer, and the reader
    //     that takes it is constructed only by the register readers; the register base only by writers.
    def "FR13: '#token' appears in exactly its owners"() {
        expect:
        holders(token) == owners.collect { GIT + it }.toSet()

        where:
        token | owners
        'RegisteredCommit::new' | ['FactoryRegisterRead.java']
        'new RegisteredCommit(' | []
        'new GitShowTip(' | [
            'DeliveredBranchReader.java',
            'GitTaskStore.java',
            'RefTipSource.java',
            'SnapshotTipCheck.java',
        ]
        'RegisterBase.' | [
            'CleanupCommit.java',
            'EnvelopeRestore.java',
            'RequiredTaskJson.java',
            'StateFileWrite.java',
        ]
        'WorktreeHeadBase' | []
        'readAtTip(' | []
        'cleanupCommitInHistory' | []
        '--grep' | []
    }

    // Over a clean tree the detector never fires, so only seeded lines tell it from a broken one.
    def "the envelope detector finds a seeded read and leaves a comment alone: #shape"() {
        expect:
        holdsEnvelope(RepoSourceTree.stripComments(source)) == detected

        where:
        shape | source || detected
        'a tip read' | 'tip.readAtTip(EnvelopePaths.TASK_JSON_PATH);' || true
        'an alias' | 'private static final String S = EnvelopePaths.STATE_JSON_PATH;' || true
        'a blob read' | 'objects.readBlob(tip, EnvelopePaths.TASK_JSON_PATH, cap);' || true
        'a comment' | '// reads EnvelopePaths.TASK_JSON_PATH at the register' || false
        'the directory' | 'runner.run(worktree, "rm", "-r", EnvelopePaths.DIR_NAME);' || false
    }

    private static List<File> ownedSources() {
        RepoSourceTree.productionSources { path ->
            OWNED_TREES.any {
                path.startsWith(it)
            }
        }
    }

    private static boolean holdsEnvelope(String code) {
        ENVELOPE_TOKENS.any { code.contains(it) }
    }

    /** Files of the owned trees whose code holds {@code token}. */
    private static Set<String> holders(String token) {
        ownedSources().findAll { RepoSourceTree.code(it).contains(token) }
        .collect { RepoSourceTree.relative(it) }
        .toSet()
    }
}
