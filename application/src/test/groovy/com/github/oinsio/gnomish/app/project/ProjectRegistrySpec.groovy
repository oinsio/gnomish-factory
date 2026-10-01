package com.github.oinsio.gnomish.app.project

import com.github.oinsio.gnomish.app.UsageException
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The project registry {@link ProjectRegistry}: registration of a clone (FR2, NFR-R1) and the
 * resolution of an operator's directory to exactly one registered clone (FR3, UX2).
 *
 * <p>Implements FR2, FR3, NFR-R1, UX2 of add-project-registry.
 */
class ProjectRegistrySpec extends Specification {

    @TempDir
    Path tmp

    FactoryHome home
    Path src

    def setup() {
        home = FactoryHome.at(tmp.resolve('home'))
        src = tmp.resolve('src')
    }

    // NFR-P1: the project's factory: block comes from the read the registry was built with
    def "NFR-P1: a project's document is kept from the one read of its file"() {
        given:
        ProjectRegistry.scan(home).add(name('widgets'), gitTree('widgets'))
        def file = home.project(name('widgets')).config()
        Files.writeString(file, Files.readString(file) + 'factory:\n  serve:\n    slots: 2\n')
        def reads = []

        when:
        def registry = ProjectRegistry.scan(home, { Path read ->
            reads << read; Files.readString(read)
        } as OperatorFile.Reader)

        then:
        reads == [file]
        registry.document(name('widgets')).first().getProperty('factory.serve.slots') == 2
        registry.document(name('gadgets')).isEmpty()
    }

    // FR2: the first clone creates the project's file
    def "the first clone creates the project and names the clone after its folder"() {
        given:
        def widgets = gitTree('widgets')

        when:
        def clone = ProjectRegistry.scan(home).add(name('widgets'), widgets)

        then:
        clone == new RegisteredClone(name('widgets'), new CloneName('widgets'), widgets, home.project(name('widgets')))
        def project = ProjectRegistry.scan(home).project(name('widgets')).orElseThrow()
        project.clones() == [clone]
        project.name() == name('widgets')
    }

    // FR2, U2: a second clone of the same project shares the project file
    def "a second clone joins the existing project"() {
        given:
        def widgets = gitTree('widgets')
        def demo = gitTree('widgets-demo')
        ProjectRegistry.scan(home).add(name('widgets'), widgets)

        when:
        ProjectRegistry.scan(home).add(name('widgets'), demo)

        then:
        ProjectRegistry.scan(home).project(name('widgets')).orElseThrow().clones()*.cloneName()*.value() as Set ==
                ['widgets', 'widgets-demo'] as Set
    }

    // NFR-R1: a clones map the edit cannot extend leaves the project file as it was
    def "a project file whose clones map is in flow style is refused and left unchanged"() {
        given:
        def file = home.project(name('widgets')).config()
        Files.createDirectories(file.parent)
        Files.writeString(file, 'clones: {}\n')

        when:
        ProjectRegistry.scan(home).add(name('widgets'), gitTree('widgets'))

        then:
        def e = thrown(UsageException)
        e.message.startsWith("$file: cannot add clone widgets to its clones map")
        Files.readString(file) == 'clones: {}\n'
        ProjectRegistry.scan(home).project(name('widgets')).orElseThrow().clones().isEmpty()
    }

    // FR2: one path, one project
    def "a path registered to any project is refused naming its owner"() {
        given:
        def widgets = gitTree('widgets')
        ProjectRegistry.scan(home).add(name('widgets'), widgets)

        when:
        ProjectRegistry.scan(home).add(name(project), widgets)

        then:
        def e = thrown(UsageException)
        e.message == "$widgets is already registered as clone widgets of project widgets"

        where:
        project << ['widgets', 'gadgets']
    }

    // FR2: the same path reached through a symlink is the same path
    def "a symlink to a registered path is refused as that path"() {
        given:
        def widgets = gitTree('widgets')
        ProjectRegistry.scan(home).add(name('widgets'), widgets)
        def link = Files.createSymbolicLink(tmp.resolve('w'), widgets)

        when:
        ProjectRegistry.scan(home).add(name('gadgets'), link)

        then:
        thrown(UsageException)
    }

    // FR2: only a git working tree is a clone
    def "a directory that is not a git working tree is refused"() {
        given:
        Files.createDirectories(src.resolve('plain'))
        def path = tmp.resolve(relative)

        when:
        ProjectRegistry.scan(home).add(name('plain'), path)

        then:
        def e = thrown(UsageException)
        e.message == "$path is not a git working tree"

        where:
        relative << ['src/plain', 'missing']
    }

    // FR2: a worktree's .git is a file, and it is a git working tree all the same
    def "a directory whose .git is a file is accepted"() {
        given:
        def worktree = Files.createDirectories(src.resolve('linked'))
        Files.writeString(worktree.resolve('.git'), 'gitdir: /elsewhere\n')

        expect:
        ProjectRegistry.scan(home).add(name('linked'), worktree).clonePath() == worktree
    }

    // FR2: clone names are unique within a project
    def "a second clone with the same folder name is refused"() {
        given:
        def first = gitTree('widgets')
        def second = gitTree('other/widgets')
        ProjectRegistry.scan(home).add(name('widgets'), first)

        when:
        ProjectRegistry.scan(home).add(name('widgets'), second)

        then:
        def e = thrown(UsageException)
        e.message == "project widgets already has a clone named widgets ($first)"
    }

    // NFR-R1: a refused registration leaves the file as it was
    def "a failed add leaves the project file unchanged"() {
        given:
        def first = gitTree('widgets')
        ProjectRegistry.scan(home).add(name('widgets'), first)
        def config = home.project(name('widgets')).config()
        def before = Files.readString(config)

        when:
        ProjectRegistry.scan(home).add(name('widgets'), gitTree('other/widgets'))

        then:
        thrown(UsageException)
        Files.readString(config) == before
    }

    // FR2: the root has no folder name to name a clone by
    def "the file-system root is refused"() {
        when:
        ProjectRegistry.scan(home).add(name('root'), Path.of('/'))

        then:
        def e = thrown(UsageException)
        e.message == '/ has no folder name to name the clone by'
    }

    // FR3: exact match by path
    def "a registered directory resolves to its clone"() {
        given:
        def widgets = gitTree('widgets')
        ProjectRegistry.scan(home).add(name('widgets'), widgets)
        def gadgets = gitTree('gadgets')
        ProjectRegistry.scan(home).add(name('gadgets'), gadgets)

        expect:
        ProjectRegistry.scan(home).resolve(widgets).project() == name('widgets')
        ProjectRegistry.scan(home).resolve(gadgets).project() == name('gadgets')
    }

    // FR3: a symlinked path reaches its registered clone
    def "a symlinked directory resolves to the clone it points at"() {
        given:
        def widgets = gitTree('widgets')
        ProjectRegistry.scan(home).add(name('widgets'), widgets)
        def link = Files.createSymbolicLink(tmp.resolve('w'), widgets)

        when:
        def clone = ProjectRegistry.scan(home).resolve(link)

        then:
        clone.project() == name('widgets')
        clone.clonePath() == widgets
    }

    // FR3, UX2: an unregistered directory is refused with the line that registers it
    def "an unregistered directory is refused with a ready-to-paste project add line"() {
        given:
        def newproj = gitTree('newproj')

        when:
        ProjectRegistry.scan(home).resolve(newproj)

        then:
        def e = thrown(UsageException)
        e.message == "$newproj is not a registered clone; register it with: gnomish project add newproj --dir=$newproj"
    }

    // FR3: a subdirectory of a clone names the clone to use
    def "a directory inside a registered clone is refused naming that clone"() {
        given:
        def widgets = gitTree('widgets')
        ProjectRegistry.scan(home).add(name('widgets'), widgets)
        def app = Files.createDirectories(widgets.resolve('app'))

        when:
        ProjectRegistry.scan(home).resolve(app)

        then:
        def e = thrown(UsageException)
        e.message == "$app is inside the registered clone $widgets of project widgets; run with --dir=$widgets"
    }

    // FR2: a clone registered at a path that no longer exists is still compared by its path
    def "a registered path that no longer exists is compared as written"() {
        given:
        def gone = tmp.resolve('gone')
        writeProject('gone', "clones:\n  gone: $gone\n")

        expect:
        ProjectRegistry.scan(home).resolve(gone).cloneName() == new CloneName('gone')
    }

    // FR2: an empty or missing home has no projects; folders without a project file are skipped
    def "only folders holding a project file are projects, listed by name"() {
        given:
        Files.createDirectories(home.projects().resolve('stray'))
        Files.writeString(home.projects().resolve('notes.txt'), 'not a project')
        writeProject('zeta', '')
        writeProject('alpha', '')

        expect:
        ProjectRegistry.scan(FactoryHome.at(tmp.resolve('nowhere'))).projects().isEmpty()
        ProjectRegistry.scan(home).projects()*.name()*.value() == ['alpha', 'zeta']
        ProjectRegistry.scan(home).project(name('stray')).isEmpty()
    }

    // FR2: a project folder is named like a project
    def "a project folder with an invalid name is refused naming the folder"() {
        given:
        writeProject('Bad Name', '')

        when:
        ProjectRegistry.scan(home)

        then:
        def e = thrown(UsageException)
        e.message.startsWith("${home.projects().resolve('Bad Name')}: invalid project name 'Bad Name'")
    }

    // UX2: the suggested name is the folder name reduced to the project-name shape
    def "the suggested project name follows the folder name"() {
        expect:
        ProjectRegistry.suggestedName(Path.of(dir)) == suggested

        where:
        dir || suggested
        '/src/newproj' || 'newproj'
        '/src/My Project' || 'my-project'
        '/src/_.hidden-1' || 'hidden-1'
        '/src/___' || 'project'
        '/' || 'project'
    }

    private Path gitTree(String relative) {
        def dir = Files.createDirectories(src.resolve(relative))
        Files.createDirectories(dir.resolve('.git'))
        dir
    }

    private void writeProject(String folder, String text) {
        def dir = Files.createDirectories(home.projects().resolve(folder))
        Files.writeString(dir.resolve('project.yaml'), text)
    }

    private static ProjectName name(String value) {
        new ProjectName(value)
    }
}
