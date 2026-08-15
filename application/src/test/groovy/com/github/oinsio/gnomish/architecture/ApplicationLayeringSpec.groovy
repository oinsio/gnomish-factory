package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * The application-layer boundary gate (FR2, UX2, M6, design D5 of split-into-modules): no class in
 * {@code :application} may depend on {@code ..adapter..}. A violation fails {@code ./gradlew check}
 * naming the violating class, which is what UX2 asks for — a build failure, not a review comment.
 *
 * <p>Gradle already makes an adapter unreachable from here: the adapters are not on this module's
 * compile classpath at all, so the import would not compile. This rule is the second, explicit
 * layer: it survives a future dependency edge being added to {@code application/build.gradle} by
 * mistake, and it states the invariant where a reader looks for it. ArchUnit resolves a reference
 * to a class outside the import scope into a stub carrying its real package name, so the rule sees
 * an {@code adapter.*} dependency whether or not that adapter is on the classpath.
 *
 * <p>The companion coverage assertion is the same guard {@code DomainPuritySpec} carries: a package
 * rename that moved application code outside the selectors below would otherwise let the gate pass
 * vacuously.
 */
class ApplicationLayeringSpec extends Specification {

    /** Every package this module owns; the union is what the layering rule constrains. */
    private static final List<String> APPLICATION_PACKAGES = [
        'com.github.oinsio.gnomish.app',
        'com.github.oinsio.gnomish.app.console',
        'com.github.oinsio.gnomish.app.findings',
        'com.github.oinsio.gnomish.app.git',
        'com.github.oinsio.gnomish.app.lease',
        'com.github.oinsio.gnomish.app.port',
        'com.github.oinsio.gnomish.app.port.agent',
        'com.github.oinsio.gnomish.app.port.check',
        'com.github.oinsio.gnomish.app.port.console',
        'com.github.oinsio.gnomish.app.port.git',
        'com.github.oinsio.gnomish.app.port.pipeline',
        'com.github.oinsio.gnomish.app.port.run',
        'com.github.oinsio.gnomish.app.serve',
        'com.github.oinsio.gnomish.app.take',
        'com.github.oinsio.gnomish.app.workspace',
        'com.github.oinsio.gnomish.board',
        'com.github.oinsio.gnomish.board.json',
        'com.github.oinsio.gnomish.dashboard',
        'com.github.oinsio.gnomish.serveobservability',
        'com.github.oinsio.gnomish.serveobservability.json',
        'com.github.oinsio.gnomish.serveobservability.writer',
        'com.github.oinsio.gnomish.status',
        'com.github.oinsio.gnomish.status.json',
        // `..usage` itself holds no class — only its `json` subpackage does.
        'com.github.oinsio.gnomish.usage.json'
    ]

    /**
     * This module's own compiled production bytecode. {@code DO_NOT_INCLUDE_TESTS} drops the
     * compiled specs, which share the root package tree and legitimately name adapter-side
     * fixtures; {@code DO_NOT_INCLUDE_JARS} drops the shared fixtures in {@code :test-fixtures},
     * which reach this classpath as a jar and sit in these very packages. The gate constrains
     * production classes only.
     */
    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
    .importPackages('com.github.oinsio.gnomish')

    def "FR2/M6: no application class depends on the adapter layer"() {
        given: 'the layering rule over every package this module owns'
        def rule = noClasses()
                .that()
                .resideInAnyPackage(APPLICATION_PACKAGES as String[])
                .should()
                .dependOnClassesThat()
                .resideInAPackage('..adapter..')

        expect: 'the rule holds over the production classes (check throws on violation)'
        rule.check(productionClasses)
    }

    def "the rule's selectors actually cover #applicationPackage"() {
        given: 'the rule\'s own package selector'
        def selector = resideInAPackage(applicationPackage)
        def packageClasses = productionClasses.findAll {
            it.packageName == applicationPackage
        }

        expect: 'the package contributes production classes for the rule to constrain'
        !packageClasses.isEmpty()

        and: 'the selector matches every one of them'
        packageClasses.every { selector.test(it) }

        where:
        applicationPackage << APPLICATION_PACKAGES
    }
}
