package com.github.oinsio.gnomish.app.project

import spock.lang.Specification

/**
 * The two names of the project layout, {@link ProjectName} and {@link CloneName}: which values
 * each accepts.
 *
 * <p>Implements FR1, FR2, FR9 of add-project-registry.
 */
class ProjectNamesSpec extends Specification {

    // FR2: a project name matches [a-z0-9][a-z0-9._-]*
    def "project name '#value' is accepted"() {
        expect:
        new ProjectName(value).value() == value
        new ProjectName(value).toString() == value

        where:
        value << [
            'widgets',
            '0',
            'a.b_c-d',
            'w1-demo.2'
        ]
    }

    // FR2: an invalid project name is refused with the accepted shape
    def "project name '#value' is refused naming the shape"() {
        when:
        new ProjectName(value)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "invalid project name '" + value + "': a project name must match [a-z0-9][a-z0-9._-]*"

        where:
        value << [
            '',
            'Widgets',
            '-widgets',
            '.widgets',
            '_w',
            'wid gets',
            'a/b',
            'widgets!',
            'widgets\n'
        ]
    }

    // FR2: names compare by content
    def "project names compare by value"() {
        expect:
        new ProjectName('widgets') == new ProjectName('widgets')
        new ProjectName('widgets') != new ProjectName('gadgets')
    }

    // FR2, FR9: a clone name keeps the directory's case and punctuation
    def "clone name '#value' is accepted"() {
        expect:
        new CloneName(value).value() == value
        new CloneName(value).toString() == value

        where:
        value << [
            'widgets',
            'Widgets Demo',
            '...',
            '.hidden',
            'a..b'
        ]
    }

    // FR1, FR9: a clone name is one folder name
    def "clone name '#printable' is refused"() {
        when:
        new CloneName(value)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "invalid clone name '" + value + "': it must be one folder name, not . or .."

        where:
        value | printable
        '' | 'empty'
        '   ' | 'blank'
        '.' | '.'
        '..' | '..'
        'a/b' | 'a/b'
        '/a' | '/a'
        'a\\b' | 'a\\b'
        '\\a' | '\\a'
        'a\0b' | 'a NUL b'
        '\0a' | 'NUL a'
    }
}
