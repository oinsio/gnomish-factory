package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.FactoryApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Specification

/**
 * Composition-root scan gate (FR3, NFR-R1, design D3 of split-into-modules,
 * task 4.8): {@code :bootstrap} is the single component-scan root, and no
 * adapter module is discovered by that scan. An adapter that wants to
 * contribute to the context says so itself — an {@code @AutoConfiguration}
 * listed in its own {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * — or is named explicitly by a composition-root {@code @Bean} method.
 *
 * <p>The distinction this spec draws is between a bean definition Spring found
 * <em>by looking</em> (a class-level definition: scanned or imported) and one
 * the composition root <em>declared</em> (a {@code @Bean} factory method, which
 * is exactly the wiring {@code :bootstrap} exists to do). Only the first kind
 * is constrained: every class-level definition must sit under a declared scan
 * root or be an explicitly exported auto-configuration.
 */
@SpringBootTest(classes = FactoryApplication)
class BootstrapScanRootSpec extends Specification {

    private static final String PRODUCTION_ROOT = 'com.github.oinsio.gnomish'
    private static final String AUTO_CONFIG_IMPORTS =
    'META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports'

    @Autowired
    ConfigurableApplicationContext context

    // FR3: the scan is rooted in the composition root's own package tree
    def "the declared scan roots reach no adapter package"() {
        given: 'the component-scan roots the composition root declares'
        def roots = FactoryApplication.getAnnotation(SpringBootApplication).scanBasePackages()

        expect: 'scanning is rooted explicitly, not left at the annotated class package'
        roots.toList() == [
            'com.github.oinsio.gnomish.app'
        ]

        and: 'no adapter package lies below any of them'
        !roots.any { "${PRODUCTION_ROOT}.adapter".startsWith(it) }
    }

    // FR3, NFR-R1: adapters are contributed explicitly, never swept up by the scan
    def "every class-level bean definition is scanned from a declared root or explicitly exported"() {
        given: 'the auto-configurations the modules on the classpath export by name'
        def exported = exportedAutoConfigurations()

        and: 'the scan roots, plus the property records the composition root names itself'
        def roots = FactoryApplication.getAnnotation(SpringBootApplication).scanBasePackages().toList()
        def namedByBootstrap = [FactoryApplication.name] +
        FactoryApplication.getAnnotation(EnableConfigurationProperties).value()*.name

        when: 'the first-party classes Spring registered by looking, not by a declared @Bean method'
        ConfigurableListableBeanFactory factory = context.beanFactory
        def foundClasses = factory.beanDefinitionNames
                .collect { factory.getBeanDefinition(it) }
                .findAll {
                    it.factoryMethodName == null && it.beanClassName != null
                }
                .collect { it.beanClassName }
                .findAll { it.startsWith(PRODUCTION_ROOT) }

        then: 'none is left unexplained by a declared scan root, an explicit export, or an explicit name'
        foundClasses.findAll { name ->
            !(name in namedByBootstrap || name in exported || roots.any {
                name.startsWith("${it}.")
            })
        }.isEmpty()

        and: 'the adapter contribution really travelled the explicit route — the export is not empty'
        exported.any { it.startsWith("${PRODUCTION_ROOT}.adapter.") }
    }

    private Set<String> exportedAutoConfigurations() {
        getClass().classLoader.getResources(AUTO_CONFIG_IMPORTS)
                .collect { it.text }
                .collectMany { it.readLines() }
                .collect { it.trim() }
                .findAll { !it.isEmpty() && !it.startsWith('#') }
                .toSet()
    }
}
