package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR2, FR4, D3 of add-factory-serve (task 5.1): {@link ServeArgumentsParser} fills {@code dir}
 * from the registered clone (FR3 of add-project-registry), defaults {@code --slots} to {@code null} (the caller
 * falls back to {@code ServeProperties#slots()}), validates a given {@code --slots} is positive,
 * carries {@code --drain} as a plain flag, and rejects every {@code take}-only flag before the
 * tracker is ever touched; {@code --interactive}, which no command accepts (FR1 of
 * remove-interactive-console), is refused as an unknown option. FR8 of
 * supervise-daemon-loops-and-embed-dashboard: {@code --dashboard} or the configured property turns
 * the embedded dashboard on, and {@code --dashboard-out} is accepted only while it is on.
 */
class ServeArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    def parser = new ServeArgumentsParser()


    /** The clone the configuration loader resolved: the {@code dir} every parse fills (FR3, D9 of add-project-registry). */
    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), Path.of('/tmp/registered-clone'))
    def "FR3: dir is the registered clone's path when --dir is absent"() {
        when:
        def parsed = parser.parse(args('serve'), CLONE, false)

        then:
        parsed.dir() == CLONE.clonePath()
        parsed.slots() == null
        !parsed.drain()
        !parsed.dashboard()
        parsed.dashboardOut() == null
    }

    def "FR3: an explicit --dir is accepted and resolves nothing — the loader already did"() {
        when:
        def parsed = parser.parse(args('serve', '--dir=/tmp/project'), CLONE, false)

        then:
        parsed.dir() == CLONE.clonePath()
    }

    def "parses an explicit positive --slots override"() {
        when:
        def parsed = parser.parse(args('serve', '--slots=4'), CLONE, false)

        then:
        parsed.slots() == 4
    }

    def "rejects a zero --slots"() {
        when:
        parser.parse(args('serve', '--slots=0'), CLONE, false)

        then:
        UsageException ex = thrown()
        ex.message.contains('--slots')
        ex.message.contains('positive')
    }

    def "rejects a negative --slots"() {
        when:
        parser.parse(args('serve', '--slots=-1'), CLONE, false)

        then:
        UsageException ex = thrown()
        ex.message.contains('--slots')
    }

    def "rejects a non-numeric --slots"() {
        when:
        parser.parse(args('serve', '--slots=many'), CLONE, false)

        then:
        UsageException ex = thrown()
        ex.message.contains('--slots')
    }

    def "parses the --drain flag"() {
        when:
        def parsed = parser.parse(args('serve', '--drain'), CLONE, false)

        then:
        parsed.drain()
    }

    def "FR8: --dashboard turns the dashboard on with the default output path"() {
        when:
        def parsed = parser.parse(args('serve', '--dashboard'), CLONE, false)

        then:
        parsed.dashboard()
        parsed.dashboardOut() == null
    }

    def "FR8: factory.serve.dashboard turns the dashboard on without the flag"() {
        when:
        def parsed = parser.parse(args('serve'), CLONE, true)

        then:
        parsed.dashboard()
    }

    def "FR8: --dashboard-out is carried when #how enables the dashboard"() {
        when:
        def parsed = parser.parse(args(*tokens), CLONE, configured)

        then:
        parsed.dashboard()
        parsed.dashboardOut() == Path.of('pages/page.html')

        where:
        how | tokens | configured
        'the flag' | [
            'serve',
            '--dashboard',
            '--dashboard-out=pages/page.html'
        ] | false
        'the property' | [
            'serve',
            '--dashboard-out=pages/page.html'
        ] | true
    }

    def "FR8: --dashboard-out resolves its value exactly as dashboard --out does"() {
        given:
        def standalone = new DashboardArgumentsParser().parse(args('dashboard', "--out=$value".toString()), CLONE)

        when:
        def parsed = parser.parse(args('serve', '--dashboard', "--dashboard-out=$value".toString()), CLONE, false)

        then:
        parsed.dashboardOut() == standalone.out()

        where:
        value << [
            'page.html',
            'nested/../page.html',
            '/abs/page.html'
        ]
    }

    // FR8, scenario "Output path without the dashboard": a usage error before anything is claimed
    def "FR8: --dashboard-out with the dashboard disabled is a usage error"() {
        when:
        parser.parse(args('serve', '--dashboard-out=page.html'), CLONE, false)

        then:
        UsageException ex = thrown()
        ex.message.contains('--dashboard-out')
        ex.message.contains('--dashboard')
        ex.message.contains('factory.serve.dashboard')
    }

    def "FR8: --dashboard-out without a value is a usage error"() {
        when:
        parser.parse(args('serve', '--dashboard', '--dashboard-out'), CLONE, false)

        then:
        UsageException ex = thrown()
        ex.message.contains('--dashboard-out requires a value')
    }

    // FR4: serve accepts none of these — --interactive included, which no command accepts any more
    def "rejects an inapplicable take-only or run-only flag"() {
        when:
        parser.parse(args('serve', "--$flag".toString()), CLONE, false)

        then:
        UsageException ex = thrown()
        ex.message.contains("--$flag")
        ex.message.contains('gnomish serve')

        where:
        flag << [
            'task',
            'task-file',
            'task-id',
            'from-stage',
            'resume',
            'base',
            'discard-work',
            'takeover',
            'interactive',
            'mode'
        ]
    }
}
