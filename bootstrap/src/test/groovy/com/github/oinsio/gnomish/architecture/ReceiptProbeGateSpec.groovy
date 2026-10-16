package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Design D5 of fix-terminal-receipt-convergence, first single-owner row: a receipt probe reads the
 * task's <b>tenure boundary</b> ({@code TrackerTask.tenureBoundary}, with the {@code finished}
 * fact), never the task's current state and never the claim-scoped {@code TrackerFacts
 * .latestBoundary}. Every pickup runs after its own claim, which has already made the task
 * {@code Working}, so a probe that asked "is it {@code AwaitingHuman}?" or "is it {@code Finished}?"
 * answered no for an effect that had landed (FR2, FR3); and the claim-scoped boundary stops at the
 * newest claim, which is exactly the one this pickup just wrote.
 *
 * <p>The probe readings live in four files since tasks 2.1 and 2.2: the two protocol probes
 * ({@code GuardedPark}, {@code FinishEffect}) and the one park and one finish reading they — and
 * every other caller — take the answer from ({@code ParkLanding}, {@code FinishLanding}). None of
 * them names the state type at all, so the ban is on the type's name, {@code TrackerTaskState},
 * rather than on one of its constants: a state read cannot be spelled without it, whatever the
 * import style — a qualified {@code TrackerTaskState.AwaitingHuman}, a nested-type import of
 * {@code AwaitingHuman} or {@code Finished} and a bare {@code instanceof}, a wildcard import of the
 * state's members: each names the type in code. {@code TakeResult.AwaitingHuman}, the take's own
 * outcome, is a different type and stays allowed (a javadoc in {@code GuardedPark} cites it).
 *
 * <p>A text scan in {@code :bootstrap}, for the reason {@link BaseHeadDefaultBoundarySpec} gives,
 * comments stripped: the probes' javadocs explain what they no longer read. Every listed file is
 * asserted reached, so a move or a rename fails the gate instead of leaving it scanning nothing.
 *
 * <p>Design D5's second row is gated here too: the cleanup commit of a delivered branch, {@code
 * finishCleanup}, is called in {@code application/src/main} and {@code bootstrap/src/main} only by
 * the files of {@link #CLEANUP_CALLERS} — the one claimless owner, {@code DeliveredCleanup}, and the
 * manual {@code run} exemption, which has no tracker to ask and cleans right behind its intent (FR10
 * of harden-task-branch-contract). A declaration or an implementation of the port method is not a
 * call; a method reference is. The list is exactly the set of files that call it, so a survivor
 * outside it fails, and so does an entry whose file no longer calls it.
 *
 * <p>Design D5's third row, the cleanup commit's one writer (design D8), is gated here as well:
 * no production source of {@code adapters/git} names the deleted worktree writer {@code
 * CleanupCommit}, or spells a {@code git rm} of the envelope directory — the cleanup commit is
 * written over bare objects, by {@code GitObjectsTerminalCommits.cleanUp}, on both media. The
 * {@code rm} of the gnome-writable {@code decisions/} subtree a consuming write makes obsolete
 * ({@code ConsumedRequestRemoval}) is not the cleanup and stays allowed.
 *
 * <p>M2, M5, FR2, FR3, FR4 of fix-terminal-receipt-convergence.
 */
class ReceiptProbeGateSpec extends Specification {

    private static final String TAKE = 'application/src/main/java/com/github/oinsio/gnomish/app/take/'

    /** The receipt probe files of design D5: the two probes and the two readings they share. */
    private static final List<String> PROBE_FILES = [
        TAKE + 'GuardedPark.java',
        TAKE + 'ParkLanding.java',
        TAKE + 'FinishEffect.java',
        TAKE + 'FinishLanding.java',
    ]

    /** What a probe file must not spell in code, each with why it would be a wrong reading. */
    private static final Map<String, Pattern> PROBE_BANS = [
        'a current-state read (TrackerTaskState)' : ~/\bTrackerTaskState\b/,
        'the claim-scoped boundary (latestBoundary)' : ~/\blatestBoundary\b/,
    ]

    private static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/app/'
    private static final String ROOT = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/'

    /** Every file that may call {@code finishCleanup} (design D5), each with why. */
    private static final Map<String, String> CLEANUP_CALLERS = [
        (APP + 'DeliveredCleanup.java') : 'the one claimless cleanup of a delivered branch (design D2)',
        (APP + 'GitOutcomeRecorder.java') : 'manual run, host: no tracker, the cleanup follows its intent (FR10)',
        (APP + 'ContainerTerminalDrive.java') : 'manual run, container: the same exemption, through the run support',
        (ROOT + 'ContainerRunSupport.java') : 'that exemption\'s hop: the support\'s finishCleanup hands to the termination',
        (ROOT + 'ContainerRunTermination.java') : 'that exemption\'s write: the support\'s store commits the cleanup',
    ]

    private static final String GIT = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/'

    /** The files the writer gate must reach: the one cleanup writer and its two callers (design D5). */
    private static final List<String> CLEANUP_WRITER_FILES = [
        GIT + 'GitObjectsTerminalCommits.java',
        GIT + 'GitTaskRepository.java',
        GIT + 'GitObjectsTaskRepository.java',
    ]

    /** What no git adapter source may spell, each with the old way it would bring back (design D8). */
    private static final Map<String, Pattern> CLEANUP_WRITER_BANS = [
        'the deleted worktree writer (CleanupCommit)' : ~/\bCleanupCommit\b/,
        'a git rm of the envelope directory' : ~/"rm"[^;]*(\bDIR_NAME\b|"\.gnomish-task\/?")/,
    ]

    /** A call or a method reference of finishCleanup; a declaration ({@code void finishCleanup(}) is neither. */
    private static final Pattern CLEANUP_CALL = ~/::\s*finishCleanup\b|(?<!\bvoid\s{1,40})\bfinishCleanup\s*\(/

    // M2, FR2, FR3: the probes read the tenure boundary, so neither banned spelling survives in them
    def "M2: no receipt probe file spells #ban"() {
        given: 'the four probe files, comments stripped — a javadoc may name what the probe no longer reads'
        def sources = RepoSourceTree.productionSources { path ->
            PROBE_FILES.contains(path)
        }

        expect: 'the scan really reached every listed file — a moved probe must fail, not pass'
        sources.collect {
            RepoSourceTree.relative(it)
        }.sort() == PROBE_FILES.toSorted()

        and: 'no probe file spells the banned reading in code'
        sources.findAll { pattern.matcher(RepoSourceTree.code(it)).find() }
        .collect { RepoSourceTree.relative(it) }
        .isEmpty()

        where:
        ban << PROBE_BANS.keySet().toList()
        pattern = PROBE_BANS[ban]
    }

    // The detector is the gate — over the clean probe files it never fires, so nothing here proves
    //     it would. A seeded state read must be found in every import style, and the outcome type
    //     of the same simple name, the tenure boundary and a javadoc mention must not be.
    def "the probe-ban detector: #shape"() {
        expect:
        PROBE_BANS.values().any {
            it.matcher(RepoSourceTree.stripComments(source)).find()
        } == detected

        where:
        shape | source || detected
        'a qualified state read' | 'return task.state() instanceof TrackerTaskState.AwaitingHuman;' || true
        'a nested-type import, bare instanceof' | 'import com.x.TrackerTaskState.Finished;\nreturn s instanceof Finished;' || true
        'a wildcard member import' | 'import com.x.TrackerTaskState.*;\nreturn s instanceof AwaitingHuman;' || true
        'the claim-scoped boundary' | 'var b = facts.latestBoundary(ref);' || true
        'the tenure boundary' | 'return task.tenureBoundary() == BoundaryKind.PARK;' || false
        'the finished fact' | 'return task.finished() || task.tenureBoundary() == BoundaryKind.FINISH;' || false
        'the take outcome type' | 'return new TakeResult.AwaitingHuman(reason);' || false
        'a javadoc mention' | '/** Never {@code TrackerTaskState.AwaitingHuman}, never latestBoundary. */\nint x;' || false
    }

    // FR4, design D5: one claimless owner of the delivered cleanup; manual run is the one exemption
    def "FR4: finishCleanup is called in application and bootstrap only by the D5 allowlist"() {
        given: 'every production source of the two layers, comments stripped'
        def sources = RepoSourceTree.productionSources { path ->
            path.startsWith('application/src/main/') || path.startsWith('bootstrap/src/main/')
        }

        expect: 'the scan reached every allowlisted file — a moved caller must fail, not pass'
        CLEANUP_CALLERS.keySet().every { listed ->
            sources.any { RepoSourceTree.relative(it) == listed }
        }

        and: 'the files that call it are exactly the allowlist: no survivor outside, no stale entry inside'
        sources.findAll { CLEANUP_CALL.matcher(RepoSourceTree.code(it)).find() }
        .collect { RepoSourceTree.relative(it) }
        .sort() == CLEANUP_CALLERS.keySet().toSorted()
    }

    // The detector is the gate: a call in every spelling must be found, and the port's declaration,
    //     its implementations, a longer name and a javadoc link must not.
    def "the finishCleanup detector: #shape"() {
        expect:
        CLEANUP_CALL.matcher(RepoSourceTree.stripComments(source)).find() == detected

        where:
        shape | source || detected
        'a qualified call' | 'store.finishCleanup(taskId);' || true
        'a bare call' | 'finishCleanup();' || true
        'a static call' | 'ContainerRunTermination.finishCleanup(this);' || true
        'a method reference' | 'Runnable cleanup = support::finishCleanup;' || true
        'a spaced method reference' | 'Runnable cleanup = support:: finishCleanup;' || true
        'the port declaration' | 'void finishCleanup(String taskId);' || false
        'an implementation' | '@Override\npublic void finishCleanup() {\n}' || false
        'a static helper declaration' | 'static void finishCleanup(ContainerRunSupport support) {\n}' || false
        'a longer name' | 'store.finishCleanupLater(taskId);' || false
        'a javadoc link' | '/** Runs through {@link #finishCleanup()} once. */\nint x;' || false
    }

    // M5, design D8: the cleanup commit has one writer on both media; the worktree writer and its
    //     `git rm -r .gnomish-task` stay deleted
    def "M5: no git adapter source spells #ban"() {
        given: 'every production source of adapters/git, comments stripped'
        def sources = RepoSourceTree.productionSources { path ->
            path.startsWith('adapters/git/src/main/')
        }

        expect: 'the scan reached the writer and both its callers — a moved file must fail, not pass'
        CLEANUP_WRITER_FILES.every { listed ->
            sources.any { RepoSourceTree.relative(it) == listed }
        }

        and: 'no file spells the old way'
        sources.findAll { pattern.matcher(RepoSourceTree.code(it)).find() }
        .collect { RepoSourceTree.relative(it) }
        .isEmpty()

        where:
        ban << CLEANUP_WRITER_BANS.keySet().toList()
        pattern = CLEANUP_WRITER_BANS[ban]
    }

    // The detector is the gate: the old writer in each spelling must be found, and the removal of
    //     the gnome-writable subtree, the bare-object delete and a javadoc mention must not be.
    def "the cleanup-writer detector: #shape"() {
        expect:
        CLEANUP_WRITER_BANS.values().any {
            it.matcher(RepoSourceTree.stripComments(source)).find()
        } == detected

        where:
        shape | source || detected
        'the deleted writer' | 'CleanupCommit.commit(runner, worktree, taskId, epoch);' || true
        'an rm of the directory by its owner' | 'runner.run(worktree, "rm", "-r", "--ignore-unmatch", EnvelopePaths.DIR_NAME);' || true
        'an rm of the directory as a literal' | 'runner.run(worktree, "rm", "-r", ".gnomish-task");' || true
        'the consumed-request removal' | 'runner.run(worktree, "rm", "-r", "-f", "--", FactoryOwnedPaths.GNOME_WRITABLE);' || false
        'the bare-object delete' | 'List.of(new TreeEdit.DeletePath(EnvelopePaths.DIR_NAME));' || false
        'a javadoc mention' | '/** The former {@code CleanupCommit} ran git rm -r .gnomish-task. */\nint x;' || false
    }
}
