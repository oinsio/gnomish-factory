package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location
import spock.lang.Shared
import spock.lang.Specification

/**
 * The published api carries no vendor internals (FR12 of add-plugin-architecture, github-plugin
 * capability: "the plugin's private core is not in the public api").
 *
 * <p>GitHub is a plugin built over a private HTTP core — client, rate-limit accounting,
 * conditional-request cache, retry configuration. That core is the thing a second vendor's plugin
 * would replace outright, so the contract surface a third party compiles against must not contain
 * it: if a github HTTP type ever reached {@code gnomish-plugin-api}, the api would be describing one
 * vendor's plumbing as though it were the plugin contract, and every future provider would inherit
 * it.
 *
 * <p>The subject is selected by where the class was loaded from rather than by package name — the
 * api shares the {@code com.github.oinsio.gnomish.app} package space with {@code :application} and
 * {@code :bootstrap}, so only the artifact tells them apart. Whole-tree gates live in
 * {@code :bootstrap} because it is the one module that sees every layer at once.
 *
 * <p>Implements FR12 of add-plugin-architecture.
 */
class PluginApiSurfaceSpec extends Specification {

    /** Two SPI types the api certainly holds — a mis-selected subject would pass every rule vacuously. */
    private static final List<String> KNOWN_API_TYPES = [
        'com.github.oinsio.gnomish.app.TrackerAdapterFactory',
        'com.github.oinsio.gnomish.app.CheckClientFactory',
    ]

    /**
     * The compiled classes of {@code :gnomish-plugin-api} alone, picked out of the test runtime
     * classpath by their artifact — a jar or a classes directory, either of which carries the module
     * name in its location.
     */
    @Shared
    JavaClasses apiClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .withImportOption({ Location location ->
        location.contains('gnomish-plugin-api')
    } as ImportOption)
    .importPackages('com.github.oinsio.gnomish')

    def "the subject really is the published api module"() {
        expect: 'the SPI the plugin contract is built around was imported'
        KNOWN_API_TYPES.every { apiClasses.contain(it) }
    }

    // FR12: "the gnomish-plugin-api surface contains no github HTTP-client, rate-limit, cache, or
    //     retry type" — no such class lives there ...
    def "no api class is a github type"() {
        given:
        def rule = noClasses().should().haveSimpleNameContaining('Github')

        expect:
        rule.check(apiClasses)
    }

    // ... and none is reachable from there either: a signature, field or call naming one would put
    //     the vendor core back on the contract without moving a class into it.
    def "no api class depends on a github type"() {
        // The bundle's three packages are named exactly: a `..github..` pattern would also match
        // the project's own `com.github.oinsio` root, and the rule would fail over every class.
        given: 'the vendor bundle packages'
        def rule = noClasses().should().dependOnClassesThat().resideInAnyPackage(
                '..gnomish.adapter.github..',
                '..adapter.tracker.github..',
                '..adapter.check.github..')

        expect:
        rule.check(apiClasses)
    }
}
