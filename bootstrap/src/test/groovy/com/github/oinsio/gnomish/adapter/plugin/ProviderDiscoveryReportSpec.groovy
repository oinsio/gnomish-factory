package com.github.oinsio.gnomish.adapter.plugin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import java.security.CodeSource
import java.security.cert.Certificate
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * {@link ProviderDiscoveryReport}: what an operator sees at startup about the classpath's provider
 * set (NFR-O1, NFR-S3, design D6 of add-plugin-architecture).
 *
 * <p>The claim under test is the one the trust posture rests on: every discovered provider is
 * reported, per port, together with the artifact it came from — so a jar nobody meant to install is
 * visible before any task runs rather than inferred later from a stage's behaviour.
 *
 * <p>Implements NFR-O1, NFR-S3 of add-plugin-architecture.
 */
class ProviderDiscoveryReportSpec extends Specification {

    // NFR-O1: "the set of discovered providers per port is reported, each with the jar that
    //     contributed it" — the discriminator an operator configures, the artifact behind it, and
    //     the class, on one line each under a counted header naming the port.
    def "the report names the port, every discriminator, and the contributing artifact"() {
        given: 'two discovered providers of one port'
        def registry = [github: new StubProvider(), inmemory: new StubProvider()]

        when:
        def lines = ProviderDiscoveryReport.render('tracker', registry)

        then: 'a header stating how many providers the tracker port discovered'
        lines[0] == 'discovered 2 tracker provider(s):'

        and: 'one line per provider: discriminator, contributing artifact, implementing class'
        def artifact = ProviderDiscoveryReport.artifactOf(StubProvider)
        lines[1] == '  github <- ' + artifact + ' (' + StubProvider.name + ')'
        lines[2] == '  inmemory <- ' + artifact + ' (' + StubProvider.name + ')'
        lines.size() == 3
    }

    // NFR-O1: an empty registry is itself news — "the classpath contributed nothing for this port"
    //     must be visible, not an absent line an operator has to notice is missing.
    def "an empty registry is reported as such, naming the port"() {
        expect:
        ProviderDiscoveryReport.render('check', [:]) == [
            'no check providers discovered'
        ]
    }

    // NFR-O1: the report reaches the operator through the log, and the registry travels on
    //     unchanged — the reporting bean wraps its own result rather than growing a branch.
    def "reporting logs every line and returns the registry unchanged"() {
        given:
        def appender = attachAppender()
        def registry = [github: new StubProvider()]

        when:
        def returned = ProviderDiscoveryReport.reported('tracker', registry)

        then: 'the same registry instance is handed back'
        returned.is(registry)

        and: 'every rendered line was logged at INFO'
        appender.list*.formattedMessage == ProviderDiscoveryReport.render('tracker', registry)
        appender.list.every { it.level == Level.INFO }

        cleanup:
        detachAppender(appender)
    }

    // NFR-O1: "including which jar contributed each provider" — a packaged distribution loads
    //     providers from jars, and the jar's own file name is what an operator recognises.
    def "a provider loaded from a jar is reported by that jar's file name"() {
        expect:
        ProviderDiscoveryReport.artifactOfSource(codeSource('file:/opt/gnomish/lib/github-1.2.3.jar')) == 'github-1.2.3.jar'
    }

    // NFR-O1: on a development classpath providers come from a classes directory; the directory
    //     identifies the module just as well, so it is reported as-is rather than as "unknown".
    def "a provider loaded from a directory is reported by that directory"() {
        expect:
        ProviderDiscoveryReport.artifactOfSource(codeSource('file:/build/adapters/github/classes/')) ==
                '/build/adapters/github/classes/'
    }

    // NFR-O1: a class whose origin the JVM does not expose is reported honestly rather than
    //     silently dropped from the report — an unreported provider is exactly what must not happen.
    def "a provider with #description is reported as unknown"() {
        expect:
        ProviderDiscoveryReport.artifactOfSource(source) == ProviderDiscoveryReport.UNKNOWN_ARTIFACT

        where:
        description | source
        'no code source at all' | null
        'a code source carrying no location' | new CodeSource(null, (Certificate[]) null)
    }

    // The class-taking form reads the origin off the class itself: a boot-classpath class has no
    // code source, an ordinary one does.
    def "the artifact of a class is read from the class's own code source"() {
        expect:
        ProviderDiscoveryReport.artifactOf(String) == ProviderDiscoveryReport.UNKNOWN_ARTIFACT
        ProviderDiscoveryReport.artifactOf(ProviderDiscoveryReport) != ProviderDiscoveryReport.UNKNOWN_ARTIFACT
    }

    private static CodeSource codeSource(String location) {
        new CodeSource(URI.create(location).toURL(), (Certificate[]) null)
    }

    private static ListAppender attachAppender() {
        def appender = new ListAppender()
        appender.start()
        ((Logger) LoggerFactory.getLogger(ProviderDiscoveryReport)).addAppender(appender)
        appender
    }

    private static void detachAppender(ListAppender appender) {
        ((Logger) LoggerFactory.getLogger(ProviderDiscoveryReport)).detachAppender(appender)
    }
}

/** A stand-in provider: the report cares only about a discovered factory's class and origin. */
class StubProvider {
}
