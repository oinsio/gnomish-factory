package com.github.oinsio.gnomish.distribution

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.jar.JarFile
import spock.lang.Shared
import spock.lang.Specification

/**
 * The boot jar and its SBOM (FR7, NFR-R1 of add-release-pipeline; design D7).
 *
 * <p>The jar carries no SBOM: the CycloneDX plugin is applied to the root project by {@code
 * sbom-conventions}, never to {@code :bootstrap}, where Spring Boot would embed the aggregate SBOM
 * as {@code META-INF/sbom/application.cdx.json} and point at it from the manifest. That SBOM
 * records when it was generated, so an embedded one would make every jar and archive differ (M2);
 * the release publishes it as a separate asset. Red with the plugin applied in {@code :bootstrap}.
 *
 * <p>The separate SBOM lists every library the jar bundles: {@code bootJar} leaves out Boot's
 * jarmode tools, which come from the Boot plugin's own classpath rather than a dependency and so
 * would be the one bundled jar no SBOM names. Red with {@code includeTools} back on.
 *
 * <p>Reads the built boot jar at {@code e2e.jarPath} ({@code verification.gradle}) and the SBOM at
 * {@code e2e.sbomPath} ({@code packaging.gradle}).
 */
class BootJarSbomSpec extends Specification {

    @Shared
    String jarPath = property('e2e.jarPath')

    def "FR7: the boot jar has no META-INF/sbom/ entry and no Sbom-Location manifest attribute"() {
        expect:
        new JarFile(jarPath).withCloseable { jar ->
            def sbomEntries = jar.entries().toList()*.name.findAll {
                it.startsWith('META-INF/sbom/')
            }
            def attributes = jar.manifest.mainAttributes
            assert sbomEntries == []
            assert attributes.getValue('Sbom-Location') == null
            assert attributes.getValue('Sbom-Format') == null
            true
        }
    }

    def "FR7: every library in the boot jar's BOOT-INF/lib/ is a component of the SBOM"() {
        given:
        def bom = new ObjectMapper().readTree(new File(property('e2e.sbomPath')))
        def components = bom.get('components').collect {
            "${it.get('name').asText()}-${it.get('version').asText()}.jar".toString()
        } as Set

        when:
        def bundled = new JarFile(jarPath).withCloseable { jar ->
            jar.entries().toList()*.name.findAll {
                it.startsWith('BOOT-INF/lib/') && it.endsWith('.jar')
            }.collect {
                it.substring('BOOT-INF/lib/'.length())
            }
        }

        then: 'the comparison reached the libraries'
        bundled.size() > 10

        and:
        bundled.findAll { !(it in components) } == []
    }

    private static String property(String name) {
        def value = System.getProperty(name)
        assert value != null: "${name} is not set (see verification.gradle and packaging.gradle)"
        value
    }
}
