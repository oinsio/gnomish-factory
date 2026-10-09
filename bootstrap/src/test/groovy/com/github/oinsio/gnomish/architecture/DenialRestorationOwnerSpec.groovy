package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D7/D11 of make-checkpoint-gate-durable: the recorded denial position is an input of
 * building a box, never a step after it. {@code ContainerEnvironments.roundEnvironment()} evaluates
 * its {@code Supplier<DenialRestoration>} as it builds the round box and offers the result — the
 * one call site of the offer. The port declares it ({@code TaskExecutionEnvironment}), and the
 * environment adapters hand it down to the guard that applies it ({@code LeasedEnvironment} →
 * {@code SelfCheckedEnvironment} → {@code EgressGuard}). A second caller — the deleted {@code
 * SandboxRunSupport.restoreDenials()} was one — would make the offer a step again, ordered against
 * {@code reattachFor} by whichever path happened to run first, and a resume would replay the
 * container's whole denial log. The deleted setter makes a late offer impossible on {@code
 * ContainerEnvironments}; this scan keeps any other component from making one on an environment.
 *
 * <p>The allowlist, by file; every entry must still name the call, so a rename cannot leave the
 * gate naming nothing.
 *
 * <p><b>{@code :test-fixtures} is excluded, on purpose.</b> {@code LocalBoxEnvironment} implements
 * the port as a test stand-in for a guarded box, and {@code TaskExecutionEnvironmentContract} is
 * the port's contract suite that every adapter passes — both exercise the port as a spec does, ship
 * in no artifact, and are no owner of the offer. Every other production source is scanned.
 *
 * <p>FR17 of make-checkpoint-gate-durable.
 */
class DenialRestorationOwnerSpec extends Specification {

    private static final String ENV = 'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/'

    /** The files allowed to name {@code restoreDenials(}, each with why. */
    private static final Map<String, String> ALLOWED = [
        ('sandbox/core/src/main/java/com/github/oinsio/gnomish/sandbox/TaskExecutionEnvironment.java'): 'the port declaration',
        (ENV + 'ContainerEnvironments.java') : 'the one offer, as the round box is built',
        (ENV + 'LeasedEnvironment.java') : 'hands the offer to the leased box',
        (ENV + 'SelfCheckedEnvironment.java') : 'hands the offer to its guard',
        (ENV + 'EgressGuard.java') : 'applies the offer to its denial cursor',
    ]

    // FR17: the offer is made at build time only; no other component calls it on an environment
    def "FR17: restoreDenials( appears in production only in the allowlisted files"() {
        given: 'every production source but the test-support fixtures, comments stripped'
        def sources = RepoSourceTree.productionSources { String path ->
            !path.startsWith('test-fixtures/')
        }

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def namers = sources.findAll { namesRestore(RepoSourceTree.code(it)) }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'each allowlisted file is reached; no other file names the call'
        namers == ALLOWED.keySet().sort()
    }

    // The detector is the gate — a seeded call must be found, a comment or a longer name must not
    def "the detector: #shape"() {
        expect:
        namesRestore(RepoSourceTree.codeOnly(line)) == detected

        where:
        shape | line || detected
        'a call on an environment' | 'environment.restoreDenials(offer);' || true
        'a spaced call' | 'lease.current().restoreDenials (offer);' || true
        'a call in a lambda' | 'lease.currentIfLeased().ifPresent(e -> e.restoreDenials(o));' || true
        'a declaration' | 'public void restoreDenials(DenialRestoration restoration) {' || true
        'a javadoc mention' | ' * offered through {@link TaskExecutionEnvironment#restoreDenials(DenialRestoration)}' || false
        'a trailing comment' | 'var r = restorations.get(); // was support.restoreDenials()' || false
        'a longer identifier' | 'support.restoreDenialsLater(offer);' || false
    }

    private static boolean namesRestore(String code) {
        code =~ /\brestoreDenials\s*\(/
    }
}
