package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR3, FR4, design D9 (second row) of remove-interactive-console: the run's external-check client
 * has one owner. {@code CheckEquipment} builds the provider-dispatching composite over the
 * operator's {@code factory.check} sections, {@code RunLaw} wraps it in the pin guard, and each
 * provider's own {@code CheckClientFactory} builds that provider's client behind the dispatcher.
 * No other production file constructs an {@code ExternalCheckClient} — so a console-backed client,
 * or any other stand-in for a provider that is not configured, cannot re-enter the run without
 * failing this spec.
 *
 * <p>The implementing class names are derived from the tree rather than listed: the github
 * provider's client is named {@code GithubCheckExternalClient}, which a suffix pattern would miss,
 * and a new implementor must be caught whatever it is called. {@code test-fixtures} is outside the
 * scan: its {@code src/main} is the test tree's own support module (the scripted client and its
 * contract spec), not production.
 *
 * <p>Over the clean tree the scan finds only the allowlisted constructions, so a broken detector
 * would look exactly like a clean tree; the seeded scenario below tells the two apart, the shape
 * {@link BaseHeadDefaultBoundarySpec} uses.
 */
class ExternalCheckWiringBoundarySpec extends Specification {

    /** A class or record declaring that it implements the port. */
    private static final Pattern IMPLEMENTOR =
    ~/(?s)\b(?:class|record)\s+(\w+)\b[^{;]*?\bimplements\b[^{;]*?\bExternalCheckClient\b/

    /**
     * Every file allowed to construct an {@code ExternalCheckClient}, with the one class it
     * constructs. The first two are the owner and its guard; the rest are the provider side of the
     * seam — a {@code CheckClientFactory} building its own provider's client, which only the
     * dispatcher ever asks for.
     */
    private static final Map<String, String> ALLOWED_CONSTRUCTIONS = [
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/CheckEquipment.java' : 'ProviderDispatchingExternalCheckClient',
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/RunLaw.java' : 'PinCheckedExternalCheckClient',
        'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/http/HttpCheckClientFactory.java' : 'HttpExternalCheckClient',
        'adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/check/github/GithubCheckClientFactory.java' : 'GithubCheckExternalClient',
        'gnomish-plugin-api/sample/src/main/java/com/github/oinsio/gnomish/sample/SampleCheckAdapter.java' : 'SampleExternalCheckClient',
    ]

    @Shared
    List<File> sources = RepoSourceTree.productionSources { path ->
        !path.startsWith('test-fixtures/')
    }

    @Shared
    Set<String> implementors = implementorsIn(sources.collect {
        RepoSourceTree.code(it)
    })

    def "the implementors of the port are derived from the tree, the known ones among them"() {
        expect: 'the scan really reached the source tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'the derivation found the owner, the guard and every allowlisted provider client'
        implementors.containsAll(ALLOWED_CONSTRUCTIONS.values())
    }

    // D9 second row: every construction in production is the owner's, the guard's, or a provider
    //     factory's own client — and each allowlisted file still makes its construction, so an
    //     entry left behind after the code moved fails here instead of quietly permitting a file.
    def "every ExternalCheckClient construction in production is allowlisted, and every allowlisted one exists"() {
        when:
        Map<String, Set<String>> found = [:]
        sources.each { file ->
            def constructed = constructionsIn(RepoSourceTree.code(file), implementors)
            if (!constructed.isEmpty()) {
                found[RepoSourceTree.relative(file)] = constructed
            }
        }

        then: 'nothing outside the allowlist constructs a client'
        found.findAll { path, constructed ->
            constructed != [ALLOWED_CONSTRUCTIONS[path]] as Set
        } == [:]

        and: 'the scan reached every allowlisted file and found its construction there'
        found.keySet() == ALLOWED_CONSTRUCTIONS.keySet()
    }

    // The detector is the gate: over the clean tree it only ever confirms the allowlist. A planted
    //     console-backed client must be found whatever it is named — and a mention in a comment, or
    //     a constructor of an unrelated class, must not be.
    def "the detector finds a planted construction and leaves non-constructions alone: #shape"() {
        given:
        def names = implementorsIn([source])

        expect:
        constructionsIn(codeOf(source), names + ALLOWED_CONSTRUCTIONS.values()) == detected as Set

        where:
        shape | source || detected
        'a renamed console client declared and built' | 'final class ConsoleCi implements ExternalCheckClient {}\nvar c = new ConsoleCi(console);' || ['ConsoleCi']
        'an anonymous client' | 'return new ExternalCheckClient() { };' || ['ExternalCheckClient']
        'a fully qualified construction' | 'return new com.acme.ci.ConsoleCi(console);\nfinal class ConsoleCi implements ExternalCheckClient {}' || ['ConsoleCi']
        'a known client outside its owner' | 'var c = new ProviderDispatchingExternalCheckClient(r, c, s);' || [
            'ProviderDispatchingExternalCheckClient'
        ]
        'a javadoc mention' | '/** once built {@code new PinCheckedExternalCheckClient(c)} here */' || []
        'an unrelated constructor' | 'var r = new CheckRunContext();' || []
    }

    // The console client this change deletes, planted back: declared, it is an implementor, so
    //     its construction is found — the seeded twin of the tree scan above.
    def "a still-declared console client is caught when constructed"() {
        given:
        def declared = implementorsIn([
            'public final class InteractiveExternalCheckClient implements ExternalCheckClient {}'
        ])

        expect:
        constructionsIn('return new InteractiveExternalCheckClient(console);', declared) == [
            'InteractiveExternalCheckClient'
        ] as Set
    }

    /** A seeded source with its comments removed, judged exactly as a file of the tree is. */
    private static String codeOf(String source) {
        source.readLines().collect { RepoSourceTree.codeOnly(it) }.join('\n')
    }

    private static Set<String> implementorsIn(List<String> codes) {
        codes.collectMany { code ->
            IMPLEMENTOR.matcher(code).results().map {
                it.group(1)
            }.toList()
        } as Set
    }

    /**
     * The port implementors {@code code} constructs, the anonymous and the fully qualified forms
     * included — a qualified name is how a construction would dodge a bare-name pattern.
     */
    private static Set<String> constructionsIn(String code, Set<String> names) {
        def alternatives = (names + ['ExternalCheckClient']).collect {
            Pattern.quote(it)
        }.join('|')
        def construction = Pattern.compile(/\bnew\s+(?:[\w$]+\s*\.\s*)*(/ + alternatives + /)\s*(?:<[^>]*>)?\s*\(/)
        construction.matcher(code).results().map { it.group(1) }.toList() as Set
    }
}
