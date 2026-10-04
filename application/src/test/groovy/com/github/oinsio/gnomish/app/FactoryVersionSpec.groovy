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
 * holding — or not holding — the build info Boot writes into the jar.
 */
class FactoryVersionSpec extends Specification {

    @TempDir
    Path root

    def "FR6: the version is the build info's build.version"() {
        given:
        buildInfo('build.artifact=bootstrap\nbuild.version=1.2.3\n')

        expect:
        FactoryVersion.readFrom(loader()) == new FactoryVersion('1.2.3')
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
    }

    def "the version reads as its value"() {
        expect:
        new FactoryVersion('2.0.0').toString() == '2.0.0'
    }

    def "a blank version is refused"() {
        when:
        new FactoryVersion(' ')

        then:
        thrown(IllegalArgumentException)
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
