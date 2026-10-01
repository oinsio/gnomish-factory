package com.github.oinsio.gnomish.config

import org.springframework.boot.env.OriginTrackedMapPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.origin.OriginTrackedValue
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ByteArrayResource
import spock.lang.Specification

/**
 * {@link SettingOrigin}: where one property of one source was set, classified once for both the
 * violation report (UX1) and {@code project show} (FR4) — the command line in either form, the
 * one-based line of a text resource, or a source that tracks no line.
 *
 * <p>Implements FR4, FR7, UX1 of add-project-registry.
 */
class SettingOriginSpec extends Specification {

    // FR7, UX1: --key and -Dkey are both the command line, told apart for the wording
    def "a command-line argument and a JVM system property are the command line in its two forms"() {
        given:
        def arguments = new SimpleCommandLinePropertySource(['--factory.serve.slots=2'] as String[])
        def system = new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, ['factory.serve.slots': '2'])

        expect:
        SettingOrigin.of(arguments, 'factory.serve.slots') == new SettingOrigin.CommandLine()
        SettingOrigin.of(system, 'factory.serve.slots') == new SettingOrigin.SystemProperty()
        SettingOrigin.isCommandLine(arguments)
        SettingOrigin.isCommandLine(system)
        !SettingOrigin.isCommandLine(new MapPropertySource('other', [:]))
    }

    // FR4, UX1: Spring's zero-based line becomes the one-based line an editor shows
    def "a key of a text resource is its one-based line"() {
        given:
        def resource = new ByteArrayResource("factory:\n  serve:\n    slots: 2\n".bytes, 'inline yaml')
        def source = new YamlPropertySourceLoader().load('inline', resource).first()

        expect:
        SettingOrigin.of(source, 'factory.serve.slots') == new SettingOrigin.TextLine(resource, 3)
    }

    // FR4: a text origin that names no resource still carries its line
    def "a text origin without a resource keeps its line"() {
        given:
        def origin = new TextResourceOrigin(null, new TextResourceOrigin.Location(2, 0))

        expect:
        SettingOrigin.of(tracked(origin), 'factory.serve.slots') == new SettingOrigin.TextLine(null, 3)
    }

    // FR4, UX1: no line to name — the reader falls back to the origin or the source's name
    def "a source with no line is other, carrying whatever origin it tracks"() {
        given:
        def lineless = new TextResourceOrigin(new ByteArrayResource(new byte[0]), null)

        expect:
        SettingOrigin.of(tracked(lineless), 'factory.serve.slots') == new SettingOrigin.Other(lineless)
        SettingOrigin.of(new MapPropertySource('plain', ['factory.serve.slots': '2']), 'factory.serve.slots') ==
        new SettingOrigin.Other(null)
    }

    private static OriginTrackedMapPropertySource tracked(TextResourceOrigin origin) {
        new OriginTrackedMapPropertySource('tracked', ['factory.serve.slots': OriginTrackedValue.of('2', origin)])
    }
}
