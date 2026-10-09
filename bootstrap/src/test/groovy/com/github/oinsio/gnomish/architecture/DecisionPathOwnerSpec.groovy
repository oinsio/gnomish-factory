package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D7/D10 of make-checkpoint-gate-durable: a decision request is live only under the token
 * of the round that owns it, so its path has one spelling — {@code
 * HarvestedBoundaryCheck.decisionPath(key, token)} — over one directory constant, {@code
 * EnvelopePaths.DECISIONS_DIR}. A second spelling (a token-less name, a hand-joined
 * {@code DECISIONS_DIR + "/" + ...}) would compile, read correctly in its own spec, and reopen the
 * stale-request defect the token closed: a file under another name read as a fresh question. The
 * parameter type keeps the one spelling honest; this scan keeps it the only one.
 *
 * <p>The allowlist, by file: the constant's owner ({@code EnvelopePaths}); the gnome-writable
 * directory ({@code FactoryOwnedPaths}, a directory, not a file name — {@code
 * ConsumedRequestRemoval} reaches it through {@code FactoryOwnedPaths.GNOME_WRITABLE}, so the
 * remover needs no entry here); the spelling and its carve-out ({@code HarvestedBoundaryCheck});
 * and the two readers that take the spelling, never compose one ({@code BranchDecisionFile} — the
 * path handed to the gnome and the one path read — and {@code SnapshotTipCheck} — the request in a
 * snapshot's tree on resume). Every allowlisted file must still name a token, so a rename cannot
 * leave the gate naming nothing.
 *
 * <p>The whole production tree is scanned, {@code :test-fixtures} included: a fixture that spelled
 * the path by hand would let a fake drift from the owner it stands in for. Comments are stripped.
 *
 * <p>FR13, FR15 of make-checkpoint-gate-durable.
 */
class DecisionPathOwnerSpec extends Specification {

    private static final String GIT = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/'

    /** The files allowed to name the directory constant or the path's one spelling, each with why. */
    private static final Map<String, String> ALLOWED = [
        ('domain/src/main/java/com/github/oinsio/gnomish/domain/branch/EnvelopePaths.java'): 'owns DECISIONS_DIR',
        (GIT + 'FactoryOwnedPaths.java') : 'the gnome-writable directory',
        (GIT + 'HarvestedBoundaryCheck.java'): 'the one spelling of the path, and its carve-out',
        (GIT + 'BranchDecisionFile.java') : 'the path handed to the gnome and the one path read',
        (GIT + 'SnapshotTipCheck.java') : 'the request read from a snapshot on resume',
    ]

    // FR13, FR15: the directory constant and the path's spelling are named only by the allowlist
    def "FR13, FR15: DECISIONS_DIR and decisionPath( appear in production only in the allowlisted files"() {
        given: 'every production source, comments stripped'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def namers = sources.findAll {
            namesDecisionPath(RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'each allowlisted file is reached and names a token; no other file does'
        namers == ALLOWED.keySet().sort()
    }

    // The detector is the gate — a seeded spelling must be found, a comment that names it must not
    def "the detector: #shape"() {
        expect:
        namesDecisionPath(RepoSourceTree.codeOnly(line)) == detected

        where:
        shape | line || detected
        'a hand-joined path' | 'String p = EnvelopePaths.DECISIONS_DIR + "/" + key.stage() + ".json";' || true
        'a static-imported constant' | 'String p = DECISIONS_DIR + "/x.json";' || true
        'a call of the spelling' | 'var p = HarvestedBoundaryCheck.decisionPath(key, token);' || true
        'a javadoc mention' | ' * named by {@link HarvestedBoundaryCheck#decisionPath(AttemptKey, RoundToken)}' || false
        'a trailing comment' | 'var p = handle.relativePath(); // was decisionPath(key)' || false
        'a longer identifier' | 'var p = round.decisionFilePath();' || false
        'a longer constant' | 'var d = DECISIONS_DIR_LEGACY;' || false
    }

    private static boolean namesDecisionPath(String code) {
        code =~ /\bDECISIONS_DIR\b/ || code =~ /\bdecisionPath\s*\(/
    }
}
