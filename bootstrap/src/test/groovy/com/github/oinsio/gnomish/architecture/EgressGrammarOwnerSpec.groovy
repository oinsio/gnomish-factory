package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR17, design D16 of migrate-verify-to-executor-contract (row {@code :httpegress} of the D9
 * single-owner table): the egress entry grammar has one owner, {@code EgressEntries} in the
 * {@code :httpegress} leaf. Its {@code *.}-prefix matching and its address-class words are spelled
 * there and nowhere else in production code, so no reader of an egress list — the box guard, the
 * law loader, the describe preflight, the in-process allowlist, the operator configuration check —
 * can grow a second, drifting copy of the grammar (the undeclared pair D8 dissolved:
 * {@code EgressAllowlist} once matched {@code *.} wildcards on its own).
 *
 * <p><b>The shapes.</b> A string literal opening with the wildcard prefix ({@code "*."}), a
 * {@code startsWith} on the owner's {@code WILDCARD_PREFIX} constant, and the class words {@code
 * loopback} and {@code private} as whole string literals. A reader names the owner's constants and
 * methods instead; a message composed from them is not a copy.
 *
 * <p><b>The one exemption</b> is the declared pair's other end: the box guard's mitmproxy addon in
 * {@code EgressGuardConfig}, Python inside a Java constant, which matches the {@code sandbox} subset
 * by hand because it runs in the guard container where no Java class can (design D8, D16;
 * {@code manual-sync-pairs.md}, the no-shared-classpath table). {@code
 * EgressGuardMatcherIdentitySpec} keeps the two ends answering alike.
 *
 * <p>The whole production tree is scanned, {@code :test-fixtures} included, comments stripped; the
 * owner and the exemption are asserted reached and still spelling the shapes, so a move cannot
 * leave the allowlist naming nothing.
 */
class EgressGrammarOwnerSpec extends Specification {

    /** The owner of the grammar; asserted reached. */
    static final String OWNER = 'httpegress/src/main/java/com/github/oinsio/gnomish/httpegress/EgressEntries.java'

    /** The exemptions, each with its reason; asserted reached. */
    static final Map<String, String> EXEMPT = [
        ('sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/EgressGuardConfig.java'):
        'the declared pair\'s other end: the box guard\'s Python addon matches the sandbox subset by hand inside the guard container, where EgressEntries cannot run (design D8, D16 of migrate-verify-to-executor-contract)',
    ]

    static final String SHAPES = /"\*\.|\bstartsWith\s*\(\s*(EgressEntries\s*\.\s*)?WILDCARD_PREFIX\b|(?i)"(loopback|private)"/

    // FR17, D16: the grammar's spellings live in its owner, and in the declared pair's other end only
    def "FR17: the wildcard prefix and the class words are spelled in EgressEntries only"() {
        given: 'every production source, comments stripped'
        def tree = productionCode()

        expect: 'the scan really reached the tree'
        tree.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'the owner is reached and spells the grammar'
        spells(tree[OWNER] ?: '')

        and: 'each exemption is reached and still spells it'
        EXEMPT.keySet().toList().findAll {
            !spells(tree[it] ?: '')
        } == []

        and: 'no other file does'
        offenders(tree) == []
    }

    // FR17: a planted second copy of the wildcard match is reported by the same verdict
    def "FR17: a planted source matching wildcards on its own is reported"() {
        given:
        def planted = '''
            final class PlantedMatcher {
                boolean permits(String entry, String host) {
                    return entry.startsWith("*.") && host.endsWith(entry.substring(1));
                }
            }
        '''
        def path = 'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/PlantedMatcher.java'
        def tree = productionCode() + [(path): RepoSourceTree.stripComments(planted)]

        expect:
        offenders(tree) == [path]
    }

    // The detector is the gate: every spelling of the grammar is found, a use of the owner is not
    def "the detector: #shape"() {
        expect:
        spells(RepoSourceTree.stripComments(source)) == detected

        where:
        shape | source || detected
        'the wildcard literal' | 'static final String W = "*.";' || true
        'a wildcard match by literal' | 'if (e.startsWith("*.example")) {}' || true
        'a match on the owner\'s constant' | 'if (e.startsWith(EgressEntries.WILDCARD_PREFIX)) {}' || true
        'a match on a local constant' | 'if (e.startsWith(WILDCARD_PREFIX)) {}' || true
        'the loopback class word' | 'if ("loopback".equals(e)) {}' || true
        'the private class word, any case' | 'return "Private";' || true
        'a message naming the owner\'s constant' | 'var m = "a " + EgressEntries.WILDCARD_PREFIX + "suffix wildcard";' || false
        'a prose mention inside a message' | 'var m = "an exact host or a *.suffix wildcard";' || false
        'a longer word' | 'var m = "private (RFC1918)";' || false
        'the owner\'s class word named' | 'if (EgressEntries.LOOPBACK.equals(e)) {}' || false
        'a class word in a comment' | '// "loopback"\nvar x = 1;' || false
    }

    /** Every production source by its relative path, comments stripped. */
    private static Map<String, String> productionCode() {
        RepoSourceTree.productionSources().collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }

    /** The files that spell the grammar without being the owner or an exemption. */
    private static List<String> offenders(Map<String, String> codeByPath) {
        codeByPath.findAll { path, code ->
            spells(code) && path != OWNER && !EXEMPT.containsKey(path)
        }
        .keySet().sort()
    }

    private static boolean spells(String code) {
        code =~ SHAPES
    }
}
