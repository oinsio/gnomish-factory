package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR15, design D16 of migrate-verify-to-executor-contract (row {@code :httpegress} of the D9
 * single-owner table): a JDK {@code java.net.http.HttpClient} is constructed in production code only
 * inside the {@code :httpegress} leaf, by its one exchange. A client built anywhere else sends its
 * requests past the egress allowlist — the scheme, host and address-class rules re-judged on every
 * redirect hop — and compiles, passes its own spec and reopens the false premise D16 closed: that a
 * built-in in the factory process is under in-process egress control.
 *
 * <p><b>The rule, and why it is keyed on the factories.</b> The JDK offers two ways to obtain a
 * client, {@code HttpClient.newBuilder()} and {@code HttpClient.newHttpClient()}, and one way around
 * them, a subclass; every construction spells one of the three ({@link #CONSTRUCTION}). A file that
 * spells one must be on the allowlist, and every allowlisted file must be reached and still spell
 * one, so a move or a removal cannot leave a row naming nothing.
 *
 * <p>The whole production tree is scanned, {@code :test-fixtures} included, comments stripped. Test
 * sources are out of scope: a spec probing a container's readiness is not a factory egress.
 */
class HttpClientConstructionBoundarySpec extends Specification {

    /** The owner: the leaf's one JDK exchange, under the guard; asserted reached. */
    private static final String OWNER = 'httpegress/src/main/java/com/github/oinsio/gnomish/httpegress/JdkHttpCheckExchange.java'

    /**
     * Files outside the leaf that still construct a client, each with the task that removes it.
     * The list empties when the GitHub HTTP core moves over the guarded exchange; a row whose file
     * no longer constructs a client fails the reach assertion, so it cannot outlive its reason.
     */
    private static final Map<String, String> PENDING = [
        ('adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/github/GithubHttpClient.java'): 'the GitHub HTTP core, until task 3.0a of migrate-verify-to-executor-contract moves it into :githubapi over the guarded exchange and deletes this row',
    ]

    static final String CONSTRUCTION = /\bHttpClient\s*\.\s*(newBuilder|newHttpClient)\s*\(|\bnewHttpClient\s*\(|\bextends\s+(java\.net\.http\.)?HttpClient\b/

    // FR15, D16: only the leaf's exchange (and the rows pending their task) construct a JDK client
    def "FR15: a JDK HttpClient is constructed in production code only inside the :httpegress leaf"() {
        given: 'every production source, comments stripped'
        def tree = productionCode()

        expect: 'the scan really reached the tree'
        tree.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'the owner is reached and still constructs the client'
        constructs(tree[OWNER] ?: '')

        and: 'each pending row is reached and still constructs one'
        PENDING.keySet().toList().findAll {
            !constructs(tree[it] ?: '')
        } == []

        and: 'no other file does'
        offenders(tree) == []
    }

    // FR15: a planted bypass is reported by the same verdict the tree is judged by
    def "FR15: a planted source that builds its own HttpClient is reported"() {
        given: 'the real tree with one bypass planted beside the owner'
        def planted = '''
            final class PlantedClient {
                private final HttpClient client = HttpClient.newBuilder().build();
            }
        '''
        def path = 'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/PlantedClient.java'
        def tree = productionCode() + [(path): RepoSourceTree.stripComments(planted)]

        expect:
        offenders(tree) == [path]
    }

    // The detector is the gate: every construction form is found, a mention is not
    def "the detector: #shape"() {
        expect:
        constructs(RepoSourceTree.stripComments(source)) == detected

        where:
        shape | source || detected
        'the builder' | 'var c = HttpClient.newBuilder().build();' || true
        'the builder, fully qualified' | 'var c = java.net.http.HttpClient.newBuilder().build();' || true
        'the builder, spaced' | 'var c = HttpClient . newBuilder ( ).build();' || true
        'the default client' | 'var c = HttpClient.newHttpClient();' || true
        'the default client, statically imported' | 'var c = newHttpClient();' || true
        'a subclass' | 'final class Mine extends HttpClient {}' || true
        'a subclass, fully qualified' | 'final class Mine extends java.net.http.HttpClient {}' || true
        'a client taken as a parameter' | 'Exchange(HttpClient client) { this.client = client; }' || false
        'a request builder' | 'var r = HttpRequest.newBuilder(uri).GET().build();' || false
        'the redirect policy named' | 'var p = HttpClient.Redirect.NEVER;' || false
        'a construction in a comment' | '// HttpClient.newBuilder()\nvar x = 1;' || false
        'a longer identifier' | 'var c = MyHttpClient.newBuilderFor(x);' || false
    }

    /** Every production source by its relative path, comments stripped. */
    private static Map<String, String> productionCode() {
        RepoSourceTree.productionSources().collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }

    /** The files that construct a client without being the owner or a pending row. */
    private static List<String> offenders(Map<String, String> codeByPath) {
        codeByPath.findAll { path, code ->
            constructs(code) && path != OWNER && !PENDING.containsKey(path)
        }
        .keySet().sort()
    }

    private static boolean constructs(String code) {
        code =~ CONSTRUCTION
    }
}
