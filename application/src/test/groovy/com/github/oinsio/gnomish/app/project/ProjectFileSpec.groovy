package com.github.oinsio.gnomish.app.project

import com.github.oinsio.gnomish.app.UsageException
import java.nio.file.Path
import spock.lang.Specification

/**
 * The project file's format, {@link ProjectFile}: the clones it lists, and the text edit that adds
 * one while keeping everything the operator wrote.
 *
 * <p>Implements FR2, NFR-R1 of add-project-registry.
 */
class ProjectFileSpec extends Specification {

    static final Path FILE = Path.of('/srv/gnomish/projects/widgets/project.yaml')

    // FR2: clones are read in file order, keyed by name, paths normalized
    def "the clones map is read in file order"() {
        given:
        def text = '''\
            clones:
              widgets: /src/widgets
              widgets.old: /src/./widgets.old
            factory:
              git-network-timeout: 10m
            '''.stripIndent()

        expect:
        ProjectFile.clones(FILE, text) == [
            (new CloneName('widgets')) : Path.of('/src/widgets'),
            (new CloneName('widgets.old')): Path.of('/src/widgets.old'),
        ]
    }

    // FR2: a file without clones lists none
    def "a file without a clones map lists no clones"() {
        expect:
        ProjectFile.clones(FILE, text).isEmpty()

        where:
        text << [
            '',
            'factory:\n  serve:\n    slots: 2\n'
        ]
    }

    // FR2: a registered path is absolute
    def "a relative clone path is refused naming the file and key"() {
        when:
        ProjectFile.clones(FILE, 'clones:\n  widgets: src/widgets\n')

        then:
        def e = thrown(UsageException)
        e.message == "$FILE: clones.widgets must be an absolute path, found 'src/widgets'"
    }

    // FR2: a clone name is one folder name
    def "an invalid clone name is refused naming the file"() {
        when:
        ProjectFile.clones(FILE, 'clones:\n  "..": /src/widgets\n')

        then:
        def e = thrown(UsageException)
        e.message.startsWith("$FILE: invalid clone name '..'")
    }

    // NFR-R1: a file that is not YAML is refused, never half-read
    def "a file that is not YAML is refused naming the file"() {
        when:
        ProjectFile.clones(FILE, 'clones: [unclosed\n')

        then:
        def e = thrown(UsageException)
        e.message.startsWith("$FILE is not valid YAML: ")
    }

    // FR2: a project's first clone creates the clones map
    def "the first clone creates the clones map"() {
        expect:
        ProjectFile.withClone(FILE, '', new CloneName('widgets'), Path.of('/src/widgets')) ==
                "clones:\n  'widgets': '/src/widgets'\n"
    }

    // FR2: a file with configuration but no clones gets the map on top, the rest kept
    def "a file without a clones map gets one on top, every other line kept"() {
        given:
        def text = '# widgets\nfactory:\n  serve:\n    slots: 2'

        when:
        def edited = ProjectFile.withClone(FILE, text, new CloneName('widgets'), Path.of('/src/widgets'))

        then:
        edited == "clones:\n  'widgets': '/src/widgets'\n# widgets\nfactory:\n  serve:\n    slots: 2\n"
        ProjectFile.clones(FILE, edited).keySet()*.value() == ['widgets']
    }

    // FR2, U2: a second clone joins the existing map at its indentation, comments kept
    def "a further clone joins the map at its entries' indentation"() {
        given:
        def text = '''\
            clones:   # the working copies
                # main checkout
                widgets: /src/widgets
            factory:
                serve:
                    slots: 2
            '''.stripIndent()

        when:
        def edited = ProjectFile.withClone(FILE, text, new CloneName('widgets-demo'), Path.of('/src/widgets-demo'))

        then:
        edited == '''\
            clones:   # the working copies
                'widgets-demo': '/src/widgets-demo'
                # main checkout
                widgets: /src/widgets
            factory:
                serve:
                    slots: 2
            '''.stripIndent()
        ProjectFile.clones(FILE, edited).keySet()*.value() == ['widgets-demo', 'widgets']
    }

    // FR2: an empty clones map takes the default indentation
    def "an empty clones map takes two spaces of indentation"() {
        expect:
        ProjectFile.withClone(FILE, text, new CloneName('widgets'), Path.of('/src/widgets')) == expected

        where:
        text || expected
        'clones:\n' || "clones:\n  'widgets': '/src/widgets'\n"
        'clones:\n\nfactory: {}\n' || "clones:\n  'widgets': '/src/widgets'\n\nfactory: {}\n"
    }

    // FR2: the clones map is found wherever it sits; neither a whitespace-only line nor a comment sets the indentation
    def "a clones map below other keys, past a whitespace-only line and a comment, keeps its entries' indentation"() {
        given:
        def text = 'factory:\n  serve:\n    slots: 2\nclones:\n  \n  # main\n    widgets: /src/widgets\n'

        when:
        def edited = ProjectFile.withClone(FILE, text, new CloneName('demo'), Path.of('/src/demo'))

        then:
        edited == "factory:\n  serve:\n    slots: 2\nclones:\n    'demo': '/src/demo'\n  \n  # main\n    widgets: /src/widgets\n"
    }

    // FR2: a quote in a name or path survives the round trip
    def "a quote in the clone name or path is escaped"() {
        when:
        def edited = ProjectFile.withClone(FILE, '', new CloneName("o'brien"), Path.of("/src/o'brien"))

        then:
        ProjectFile.clones(FILE, edited) == [(new CloneName("o'brien")): Path.of("/src/o'brien")]
    }

    // NFR-R1: an edit whose result does not read back as the old clones plus the new one is refused
    def "a clones map in flow style is refused rather than given a second clones key"() {
        when:
        ProjectFile.withClone(FILE, text, new CloneName('widgets'), Path.of('/src/widgets'))

        then:
        def e = thrown(UsageException)
        e.message == "$FILE: cannot add clone widgets to its clones map; write the map in block style," +
                " one 'name: path' line under 'clones:', and run the command again"

        where:
        text << [
            "clones: {a: '/x'}\n",
            'clones: {}\n'
        ]
    }

    // NFR-R1: an edit that parses but drops the new clone is refused too
    def "a clone name the parser does not read back under clones is refused"() {
        when:
        ProjectFile.withClone(FILE, '', new CloneName('[draft]'), Path.of('/src/[draft]'))

        then:
        def e = thrown(UsageException)
        e.message.startsWith("$FILE: cannot add clone [draft] to its clones map")
    }
}
