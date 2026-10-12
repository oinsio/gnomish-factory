package com.github.oinsio.gnomish.config

import org.springframework.boot.env.OriginTrackedMapPropertySource
import org.springframework.boot.origin.OriginLookup
import org.springframework.boot.origin.OriginTrackedValue
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySource
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.io.ByteArrayResource
import spock.lang.Specification

/**
 * {@link ListShadowing}: a list a higher source sets hides every key of the same list in the
 * sources below it, whichever way either source spells the list; maps and foreign keys are left
 * alone (FR5; finding F106 of tighten-spec-contract).
 *
 * <p>Implements FR5 of add-project-registry.
 */
class ListShadowingSpec extends Specification {

    MutablePropertySources sources = new MutablePropertySources()

    // FR5 (F106): a shorter higher list leaves no lower element past its end
    def "FR5: a higher indexed list hides every indexed key of the same list below it"() {
        given:
        sources.addLast(new MapPropertySource('project', ['factory.c.acme.scopes[0]': 'project-a']))
        sources.addLast(new MapPropertySource('host', [
            'factory.c.acme.scopes[0]': 'host-a',
            'factory.c.acme.scopes[1]': 'host-b',
            'factory.c.acme.scopes[2]': 'host-c',
        ]))

        when:
        ListShadowing.apply(sources)

        then:
        sources.get('host').propertyNames as List == []
        sources.get('host').getProperty('factory.c.acme.scopes[1]') == null
        sources.get('project').getProperty('factory.c.acme.scopes[0]') == 'project-a'
    }

    // FR5 (F106): a comma-separated scalar claims the whole list
    def "FR5: a comma-separated value on the command line hides the files' indexed list"() {
        given:
        sources.addLast(new SimpleCommandLinePropertySource([
            '--factory.c.acme.scopes=cli-a,cli-b'
        ] as String[]))
        sources.addLast(new MapPropertySource('host', ['factory.c.acme.scopes[0]': 'host-a']))

        when:
        ListShadowing.apply(sources)

        then:
        sources.get('host').propertyNames as List == []
    }

    // FR5 (F106): an indexed list from a higher source replaces a lower comma-separated value
    def "FR5: a higher indexed list hides a lower scalar of the same name"() {
        given:
        sources.addLast(new MapPropertySource('systemProperties', ['factory.c.acme.scopes[0]': 'x']))
        sources.addLast(new MapPropertySource('host', ['factory.c.acme.scopes': 'a,b']))

        when:
        ListShadowing.apply(sources)

        then:
        sources.get('host').propertyNames as List == []
    }

    // FR5 (F106): Spring's relaxed spellings name one list
    def "FR5: a list spelled differently by two sources is still one list"() {
        given:
        sources.addLast(new MapPropertySource('cli', ['factory.c.acme.allowedTools[0]': 'x']))
        sources.addLast(new MapPropertySource('host', ['factory.c.acme.allowed-tools[1]': 'y']))

        when:
        ListShadowing.apply(sources)

        then:
        sources.get('host').propertyNames as List == []
    }

    // FR5 (F106): the list is cut at its first numeric index, so a list of objects goes whole
    def "FR5: a list of objects is replaced whole, nested fields included"() {
        given:
        sources.addLast(new MapPropertySource('project', ['factory.x[0].name': 'p']))
        sources.addLast(new MapPropertySource('host', ['factory.x[1].name': 'h', 'factory.x[1].tags[0]': 't']))

        when:
        ListShadowing.apply(sources)

        then:
        sources.get('host').propertyNames as List == []
    }

    // FR5: maps keep merging key by key; another list, a foreign key and one source's own keys stay
    def "FR5: map entries, other lists, foreign keys and a source's own indices are not hidden"() {
        given:
        def host = new MapPropertySource('host', [
            'factory.c.corp.endpoint': 'h',
            'factory.c.acme.other[0]': 'o',
            'other.list[0]': 'f',
            'factory.l[0]': 'a',
            'factory.l[1]': 'b',
        ])
        sources.addLast(new MapPropertySource('project', [
            'factory.c.acme.endpoint': 'p',
            'other.list[1]': 'g',
            'factory.c.acme.scopes[0]': 's',
        ]))
        sources.addLast(host)

        when:
        ListShadowing.apply(sources)

        then: 'a source with nothing to hide is left as it was'
        sources.get('host').is(host)
        host.propertyNames as Set == [
            'factory.c.corp.endpoint',
            'factory.c.acme.other[0]',
            'other.list[0]',
            'factory.l[0]',
            'factory.l[1]'
        ] as Set
    }

    // FR5: a source that cannot list its keys neither shadows nor is shadowed
    def "FR5: a non-enumerable source is passed over"() {
        given:
        def plain = new PropertySource<Object>('plain', new Object()) {
                    Object getProperty(String name) {
                        'v'
                    }
                }
        sources.addLast(plain)
        sources.addLast(new MapPropertySource('host', ['factory.l[0]': 'a']))

        when:
        ListShadowing.apply(sources)

        then:
        sources.get('plain').is(plain)
        sources.get('host').getProperty('factory.l[0]') == 'a'
    }

    // FR5, UX1: a shadowed file keeps the file-and-line origin of every key it still shows
    def "FR5: a shadowed source keeps the origin of its visible keys and has none for a hidden one"() {
        given:
        def origin = line(3)
        sources.addLast(new MapPropertySource('project', ['factory.l[0]': 'p']))
        sources.addLast(new OriginTrackedMapPropertySource('host', [
            'factory.l[0]': OriginTrackedValue.of('h', line(2)),
            'factory.serve.slots': OriginTrackedValue.of('2', origin),
        ]))

        when:
        ListShadowing.apply(sources)
        def shadowed = sources.get('host') as OriginLookup<String>

        then:
        shadowed.getOrigin('factory.serve.slots') == origin
        shadowed.getOrigin('factory.l[0]') == null
        sources.get('host').getProperty('factory.serve.slots') == '2'
    }

    private static TextResourceOrigin line(int line) {
        new TextResourceOrigin(new ByteArrayResource(new byte[0], 'factory.yaml'), new TextResourceOrigin.Location(line, 0))
    }
}
