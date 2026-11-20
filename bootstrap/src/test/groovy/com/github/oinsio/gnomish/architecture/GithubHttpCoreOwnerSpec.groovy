package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.githubapi.GithubHttpClient
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * One first-party GitHub HTTP core serves the tracker and the built-ins (FR14, M6 of
 * migrate-verify-to-executor-contract; design D15, the single-owner row of {@code :githubapi}).
 *
 * <p>Before this change the vendor bundle held the GitHub HTTP client and the check adapter reached
 * it through the bundle; moving the check into core could have left two clients, each with its own
 * credential header, cache and retry, and every unit spec green. The claim is made three ways:
 *
 * <ol>
 *   <li><b>Declared once.</b> M6's grep — {@code class GithubHttpClient} — finds one production
 *       file, in {@code githubapi/src/main}, and no other production class is named as an HTTP
 *       client.
 *   <li><b>Used by every consumer.</b> The bundle's tracker, the {@code github-actions} built-in
 *       and the {@code scm-status} built-in each depend on that class in their bytecode.
 *   <li><b>No way around it.</b> None of those consumers reaches the egress exchange or the JDK's
 *       {@code HttpClient} itself — a second client would have to — so their HTTP passes the core.
 * </ol>
 */
class GithubHttpCoreOwnerSpec extends Specification {

    private static final String OWNER = 'githubapi/src/main/java/com/github/oinsio/gnomish/githubapi/GithubHttpClient.java'

    /** M6's grep: a class declaration of the GitHub HTTP client. */
    private static final Pattern DECLARATION = ~/\bclass\s+GithubHttpClient\b/

    /** The consumers the design's single-owner row names, by their packages. */
    private static final Map<String, String> CONSUMERS = [
        'the bundle\'s tracker': 'com.github.oinsio.gnomish.adapter.tracker.github',
        'the github-actions built-in': 'com.github.oinsio.gnomish.adapter.scm.actions',
        'the scm-status built-in': 'com.github.oinsio.gnomish.adapter.scm.status'
    ]

    /** What a second client would have to call: the exchange the core sends through, or the JDK client. */
    private static final List<String> BYPASS = [
        'com.github.oinsio.gnomish.httpegress.HttpCheckExchange',
        'com.github.oinsio.gnomish.httpegress.GuardedHttpCheckExchange',
        'com.github.oinsio.gnomish.httpegress.JdkHttpCheckExchange',
        'java.net.http.HttpClient'
    ]

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    // FR14, M6: one GitHub HTTP client, declared in the first-party library and nowhere else.
    // Scenario: plugin/github-plugin / One GitHub HTTP client serves every GitHub consumer
    def "FR14, M6: exactly one GitHub HTTP client class exists, in the first-party GitHub library"() {
        given:
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the source tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'the declaration is found in one file, the library\'s'
        sources.findAll { DECLARATION.matcher(RepoSourceTree.code(it)).find() }
        .collect { RepoSourceTree.relative(it) } == [OWNER]

        and: 'no other production class is an HTTP client by name'
        productionClasses.findAll {
            !it.nestedClass && !it.anonymousClass && it.simpleName.endsWith('HttpClient')
        }
        .collect { it.name } == [GithubHttpClient.name]
    }

    // FR14: the bundle's tracker and both GitHub built-ins send through the one core.
    // Scenario: plugin/github-plugin / One GitHub HTTP client serves every GitHub consumer
    def "FR14: #consumer uses the GitHub library's client and no HTTP path of its own"() {
        given:
        def classes = productionClasses.findAll { it.packageName == pkg }

        expect: 'the package was imported, so the assertions below look at real bytecode'
        !classes.isEmpty()

        and: 'some class of it depends on the one client'
        classes.any { javaClass ->
            javaClass.directDependenciesFromSelf.any {
                it.targetClass.name == GithubHttpClient.name
            }
        }

        and: 'none reaches the exchange or the JDK client around it'
        classes.collectMany { javaClass ->
            javaClass.directDependenciesFromSelf
            .findAll { it.targetClass.name in BYPASS }
            .collect { javaClass.name + ' -> ' + it.targetClass.name }
        }
        .unique() == []

        where:
        consumer << CONSUMERS.keySet().toList()
        pkg = CONSUMERS[consumer]
    }

    // The gate's own detector: a second declaration would be seen, prose would not.
    def "the declaration detector finds a class declaration and spares #spared"() {
        expect:
        DECLARATION.matcher(RepoSourceTree.stripComments(source)).find() == found

        where:
        spared | source || found
        'nothing: a declaration' | 'public final class GithubHttpClient {' || true
        'a field of the type' | 'private final GithubHttpClient httpClient;' || false
        'a javadoc mention' | '/** Wraps {@link GithubHttpClient}: class GithubHttpClient is the core. */' || false
    }
}
