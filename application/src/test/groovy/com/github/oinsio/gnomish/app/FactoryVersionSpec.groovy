package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The one runtime reader of the product version (FR3, FR6 of add-release-pipeline; design D2):
 * each feature hands {@link FactoryVersion#readFrom} a class loader over a temporary directory
 * holding — or not holding — the build info Boot writes into the jar. The base tag the build
 * computed beside the version (design D11 of add-sandbox-base-image) travels in the same resource,
 * and a release version without a valid, consistent one is a malformed resource.
 */
class FactoryVersionSpec extends Specification {

    @TempDir
    Path root

    def "FR6: the version is the build info's build.version, with its base tag: #version"() {
        given:
        buildInfo("build.artifact=bootstrap\nbuild.version=${version}\nbuild.baseTag=${tag}\n")

        when:
        def read = FactoryVersion.readFrom(loader())

        then:
        read == new FactoryVersion(version, Optional.of(new BaseTag(tag)))
        read.baseTag().get().value() == tag

        where: 'FR9 of add-sandbox-base-image: X.Y of a final release, the whole version of a pre-release'
        version | tag
        '1.2.3' | '1.2'
        '0.4.1-rc.1' | '0.4.1-rc.1'
    }

    def "FR6: a development build's build info carries no base tag, and reads as the development version, silently"() {
        given:
        buildInfo('build.version=0.0.0-dev\n')
        def logs = LogCaptureSupport.attach(FactoryVersion, Level.DEBUG)

        when:
        def version = FactoryVersion.readFrom(loader())

        then:
        version == FactoryVersion.DEVELOPMENT
        version.baseTag().isEmpty()
        logs.list.isEmpty()
        new FactoryVersion('0.0.0-dev', Optional.empty()) == FactoryVersion.DEVELOPMENT

        cleanup:
        logs.detach()
    }

    def "FR3: without build info the factory is the development version, silently"() {
        given:
        def logs = LogCaptureSupport.attach(FactoryVersion, Level.DEBUG)

        when:
        def version = FactoryVersion.readFrom(loader())

        then:
        version == FactoryVersion.DEVELOPMENT
        version.value() == '0.0.0-dev'
        logs.list.isEmpty()

        cleanup:
        logs.detach()
    }

    def "build info that cannot yield a version reports the development version under GF150"() {
        given:
        buildInfo(content)
        def logs = LogCaptureSupport.attach(FactoryVersion, Level.DEBUG)

        when:
        def version = FactoryVersion.readFrom(loader())

        then:
        version == FactoryVersion.DEVELOPMENT
        logs.list.size() == 1
        logs.list[0].level == Level.WARN
        logs.list[0].formattedMessage.startsWith(OperatorEvent.FACTORY_VERSION_UNREADABLE.head())
        logs.list[0].throwableProxy != null

        cleanup:
        logs.detach()

        where:
        shape | content
        'a malformed escape' | 'build.version=\\u12\n'
        'no build.version key' | 'build.artifact=bootstrap\n'
        'a blank build.version' | 'build.version=   \n'
        'a release version with no build.baseTag' | 'build.version=1.2.3\n'
        'a malformed build.baseTag' | 'build.version=1.2.3\nbuild.baseTag=1\n'
        'an inconsistent build.baseTag' | 'build.version=1.2.3\nbuild.baseTag=1.3\n'
        'X.Y beside a pre-release version' | 'build.version=1.2.3-rc.1\nbuild.baseTag=1.2\n'
        'a base tag beside the development version' | 'build.version=0.0.0-dev\nbuild.baseTag=0.0\n'
    }

    def "the version reads as its value"() {
        expect:
        new FactoryVersion('2.0.0', Optional.of(new BaseTag('2.0'))).toString() == '2.0.0'
    }

    def "a blank version is refused"() {
        when:
        new FactoryVersion(' ', Optional.empty())

        then:
        thrown(IllegalArgumentException)
    }

    def "FR9 of add-sandbox-base-image: a version and a base tag that disagree are unconstructible: #version / #tag"() {
        when:
        new FactoryVersion(version, tag == null ? Optional.empty() : Optional.of(new BaseTag(tag)))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "factory version ${version} does not carry its base tag (found ${tag ?: 'none'})"

        where:
        version | tag
        '0.0.0-dev' | '0.0'
        '1.2.3' | null
        '1.2.3' | '1.3'
        '1.2.3' | '1.2.3-rc.1'
        '12.3.4' | '2.3'
        '1.2' | '1.2'
        '1.2.3-rc.1' | '1.2'
        '1.2.3-rc.1' | '1.2.3-rc.2'
        'nightly' | null
    }

    def "FR9 of add-sandbox-base-image: a consistent pair is constructed: #version / #tag"() {
        expect:
        new FactoryVersion(version, Optional.of(new BaseTag(tag))).baseTag() == Optional.of(new BaseTag(tag))

        where:
        version | tag
        '1.2.3' | '1.2'
        '10.20.30' | '10.20'
        '1.2.3-rc.1' | '1.2.3-rc.1'
    }

    def "the running process reads its version once"() {
        expect: 'the test classpath carries no build info, and every call answers the same instance'
        FactoryVersion.current() == FactoryVersion.DEVELOPMENT
        FactoryVersion.current().is(FactoryVersion.current())
    }

    private void buildInfo(String content) {
        def file = root.resolve(FactoryVersion.BUILD_INFO)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    /** No parent: only the temporary directory is visible, never the test class path. */
    private URLClassLoader loader() {
        new URLClassLoader([root.toUri().toURL()] as URL[], (ClassLoader) null)
    }
}
