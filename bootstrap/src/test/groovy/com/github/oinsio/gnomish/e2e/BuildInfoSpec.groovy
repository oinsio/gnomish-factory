package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.app.BaseTag
import com.github.oinsio.gnomish.app.FactoryVersion
import com.github.oinsio.gnomish.testsupport.TestTaskProperty
import java.util.zip.ZipFile
import spock.lang.Shared
import spock.lang.Specification

/**
 * The product version as the packaged jar carries it: Boot's {@code build-info.properties},
 * written from the {@code project.version} that {@code product-version-conventions} set, with no
 * wall-clock timestamp (FR3, NFR-R1 of add-release-pipeline; design D2, D5).
 *
 * <p>Reads the built boot jar at {@code e2e.jarPath}; the expected version arrives as
 * {@code e2e.productVersion} from the same build ({@code verification.gradle}), so the spec
 * holds for a developer build ({@code 0.0.0-dev}) and a release build alike.
 */
class BuildInfoSpec extends Specification {

    private static final String BUILD_INFO = 'META-INF/build-info.properties'

    @Shared
    Properties buildInfo = readBuildInfo()

    def "FR3: the jar's build info carries the version the build set"() {
        given:
        def expected = TestTaskProperty.required('e2e.productVersion')

        expect:
        buildInfo.getProperty('build.version') == expected
    }

    // FR9 of add-sandbox-base-image (design D11): the base tag travels beside the version
    def "FR9: the jar's build info carries the build's base tag for a release, and no key for a development build"() {
        given:
        def version = TestTaskProperty.required('e2e.productVersion')
        def expected = TestTaskProperty.required('e2e.baseTag')

        expect:
        if (version == FactoryVersion.DEVELOPMENT.value()) {
            assert expected.isEmpty()
            assert !buildInfo.containsKey('build.baseTag')
        } else {
            assert buildInfo.getProperty('build.baseTag') == expected
            // the runtime reader accepts the pair: present, well-shaped and consistent with the version
            assert new FactoryVersion(version, Optional.of(new BaseTag(expected))).baseTag().isPresent()
        }
    }

    // Scenario: build-system/quality-gates / No archive records when it was built
    // Scenario: release/release-distribution / The stamp carries no timestamp
    def "NFR-R1: the build info holds no build timestamp, so two builds of one commit agree"() {
        expect:
        !buildInfo.containsKey('build.time')
    }

    private static Properties readBuildInfo() {
        def jarPath = TestTaskProperty.required('e2e.jarPath')
        new ZipFile(jarPath).withCloseable { zip ->
            def entry = zip.getEntry(BUILD_INFO)
            assert entry != null: "${BUILD_INFO} is missing from ${jarPath}"
            def properties = new Properties()
            zip.getInputStream(entry).withCloseable { properties.load(it) }
            properties
        }
    }
}
