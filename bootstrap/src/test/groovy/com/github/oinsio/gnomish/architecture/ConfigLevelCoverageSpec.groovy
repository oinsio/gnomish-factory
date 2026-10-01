package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.FactoryApplication
import com.github.oinsio.gnomish.operatorconfig.ConfigLevel
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.lang.reflect.RecordComponent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import spock.lang.Shared
import spock.lang.Specification

/**
 * Level-coverage gate (M2, FR6 of add-project-registry; design D4): every component of every
 * {@code @ConfigurationProperties} class carries {@link ConfigLevel}, so a new {@code factory.*}
 * key cannot exist without declaring where it may be set. The key → level table the configuration
 * loader checks against is derived from these annotations; a component without one would be a key
 * the loader cannot place, which this spec turns into a build failure instead.
 *
 * <p>The scan covers the production bytecode of the whole runtime classpath — the composition root
 * sees every layer, so a properties record added in any module is reached. It recurses into nested
 * configuration records ({@code factory.tracker}, {@code factory.sandbox.limits}): such a component
 * names no key of its own, so it must carry no level, and each of its components must carry one.
 *
 * <p>Non-vacuity is asserted against the application's own registration, not a list kept here:
 * the classes {@link FactoryApplication} registers through {@code @EnableConfigurationProperties}
 * are exactly the classes the scan found, so a scan that silently reached nothing — or a
 * properties class that was never registered — fails rather than passes.
 */
class ConfigLevelCoverageSpec extends Specification {

    private static final String ROOT_PACKAGE = 'com.github.oinsio.gnomish'

    @Shared
    Set<Class<?>> propertiesClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages(ROOT_PACKAGE)
    .findAll { it.isAnnotatedWith(ConfigurationProperties) }
    .collect { it.reflect() } as Set

    def "M2: the scan reached exactly the properties classes the application registers"() {
        given: 'the registration on the application class'
        def registered = FactoryApplication.getAnnotation(EnableConfigurationProperties).value() as Set

        expect: 'the scan is not vacuous and nothing registered escaped it'
        !propertiesClasses.isEmpty()
        propertiesClasses == registered
    }

    def "M2: every @ConfigurationProperties class is a record, so its keys are its components"() {
        expect:
        propertiesClasses.findAll { !it.isRecord() }.isEmpty()
    }

    def "M2, FR6: every component of every properties record declares its level"() {
        when: 'every leaf component is visited, nested configuration records included'
        def violations = propertiesClasses.collectMany { violationsIn(it) }

        then: 'none is missing a level, and no nested-record component carries one'
        violations == []
    }

    def "M2: the scan recursed into the nested configuration records"() {
        when:
        def nested = propertiesClasses.collectMany {
            nestedRecordsOf(it)
        } as Set

        then: 'the tracker backoff and the sandbox limits records were visited'
        nested*.simpleName.toSet() == ['Tracker', 'ResourceLimits'] as Set
    }

    private static List<String> violationsIn(Class<?> record) {
        record.recordComponents.collectMany { RecordComponent component ->
            def name = "${record.simpleName}.${component.name}".toString()
            def level = component.getAnnotation(ConfigLevel)
            if (isNestedConfigRecord(component.type)) {
                def own = level == null ? [] : [
                    "${name}: a nested record names no key and carries no level".toString()
                ]
                return own + violationsIn(component.type)
            }
            level == null ? [
                "${name}: no @ConfigLevel".toString()
            ] : []
        }
    }

    private static List<Class<?>> nestedRecordsOf(Class<?> record) {
        List<Class<?>> nested = []
        record.recordComponents
                .findAll { isNestedConfigRecord(it.type) }
                .each {
                    nested.add(it.type); nested.addAll(nestedRecordsOf(it.type))
                }
        nested
    }

    private static boolean isNestedConfigRecord(Class<?> type) {
        type.isRecord() && type.name.startsWith(ROOT_PACKAGE)
    }
}
