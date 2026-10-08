package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D13 of make-checkpoint-gate-durable: in the sandbox modules and the agent adapter, a
 * monitor lives only where its lock case has been argued (`lock-scope.md`). {@code LiveBox} is the
 * one live box per role — a state-guarding lock in the three-phase shape, with every materialize
 * run unlocked — and {@code EnvironmentLease} and {@code FreshJudgeEnvironments} delegate to it
 * rather than holding a monitor of their own; {@code GuardDenialReads} guards the denial cursor and
 * runs its container reads outside the lock. A new {@code synchronized} elsewhere in these trees —
 * the shape the lease and the judge source had before D13, a materialize held under the monitor —
 * fails this scan until its lock case is argued and the file is added here.
 *
 * <p>The allowlist, by file; every entry must still hold a monitor, so a rename cannot leave the
 * gate naming nothing. Comments are stripped, and only the whole keyword counts — {@code
 * Collections.synchronizedList} is no monitor.
 *
 * <p>FR21, M9 of make-checkpoint-gate-durable.
 */
class LockScopeOwnerSpec extends Specification {

    /** The scanned trees: the sandbox modules and the agent adapter. */
    private static final List<String> SCANNED = ['sandbox/', 'adapters/agent/']

    /** The files allowed to hold a monitor, each with its lock case. */
    private static final Map<String, String> ALLOWED = [
        ('sandbox/core/src/main/java/com/github/oinsio/gnomish/sandbox/LiveBox.java') :
        'state-guarding, three-phase: the record and the in-flight claim; materialize runs unlocked',
        ('sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/GuardDenialReads.java'):
        'state-guarding: the denial cursor; the container reads run outside the lock',
    ]

    /** A floor near the real count (141 at the time of writing), so an early-stopped scan fails. */
    private static final int KNOWN_SCANNED_SOURCES = 120

    // FR21, M9: a monitor in these trees only where its lock case is argued
    def "FR21: synchronized appears in the sandbox and agent-adapter sources only in the allowlisted files"() {
        given: 'every production source of the scanned trees, comments stripped'
        def sources = RepoSourceTree.productionSources { String path ->
            SCANNED.any { path.startsWith(it) }
        }

        expect: 'the scan really reached the trees'
        sources.size() >= KNOWN_SCANNED_SOURCES

        when:
        def holders = sources.findAll { holdsMonitor(RepoSourceTree.code(it)) }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'each allowlisted file is reached and holds a monitor; no other file does'
        holders == ALLOWED.keySet().sort()
    }

    // The detector is the gate — a seeded monitor must be found, a comment or a longer name must not
    def "the detector: #shape"() {
        expect:
        holdsMonitor(RepoSourceTree.codeOnly(line)) == detected

        where:
        shape | line || detected
        'a synchronized method' | 'public synchronized Optional<Box> current() {' || true
        'a synchronized block' | 'synchronized (this) {' || true
        'a private one' | 'private synchronized void settle(Box fresh) {' || true
        'a javadoc mention' | ' * <p>Deliberately not {@code synchronized} (lock-scope.md)' || false
        'a trailing comment' | 'var b = box; // was synchronized' || false
        'a synchronized list' | 'private final List<E> events = Collections.synchronizedList(l);' || false
    }

    private static boolean holdsMonitor(String code) {
        code =~ /\bsynchronized\b/
    }
}
