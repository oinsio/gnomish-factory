package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.project.CloneName
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.AbstractEnvironment
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.io.FileSystemResource
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@code gnomish project add|list|show}, {@link ProjectCommand}: one feature per verb, including
 * the "where a value came from" scenario of the project-registry spec (FR4, NFR-O1, U5).
 *
 * <p>Implements FR2, FR4, NFR-O1 of add-project-registry.
 */
class ProjectCommandSpec extends Specification implements FactoryPropertiesFixture {

    @TempDir
    Path tmp

    FactoryHome home
    Path widgets
    ConfigurableEnvironment environment = new AbstractEnvironment() {}
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory()
    ScriptedConsoleIO console = new ScriptedConsoleIO()

    def setup() {
        home = FactoryHome.at(tmp.resolve('home'))
        widgets = gitTree('src/widgets')
    }

    // FR2: add registers the clone and says where
    def "add registers the clone and prints it with the project file"() {
        when:
        run('project', 'add', 'widgets', "--dir=$widgets")

        then:
        console.printed == [
            "registered clone widgets of project widgets: $widgets\n  project file: ${home.project(name('widgets')).config()}\n".toString()
        ]
        Files.readString(home.project(name('widgets')).config()).contains("'widgets': '$widgets'")
    }

    // FR4: list prints every project with its clones
    def "list prints each project and its clones"() {
        given:
        run('project', 'add', 'widgets', "--dir=$widgets")
        def demo = gitTree('src/widgets-demo')
        run('project', 'add', 'widgets', "--dir=$demo")
        console.printed.clear()

        when:
        run('project', 'list')

        then:
        console.printed == [
            "widgets: ${home.project(name('widgets')).dir()}\n  widgets-demo: $demo\n  widgets: $widgets\n".toString()
        ]
    }

    // FR4: an empty registry says how to register
    def "list with no project says how to register one"() {
        when:
        run('project', 'list')

        then:
        console.printed == [
            "no projects registered in ${home.projects()}; register a clone with: gnomish project add <name> --dir=<clone>\n".toString()
        ]
    }

    // FR4, NFR-O1, U5: "Where a value came from"
    def "show prints the paths and each value with the file and line it came from"() {
        given:
        run('project', 'add', 'widgets', "--dir=$widgets")
        def projectFile = home.project(name('widgets')).config()
        Files.writeString(projectFile, Files.readString(projectFile) + "factory:\n  git-network-timeout: 10m\n")
        Files.writeString(home.hostConfig(), "factory:\n  git-network-timeout: 5m\n")
        environment.propertySources.addLast(yaml(projectFile))
        environment.propertySources.addLast(yaml(home.hostConfig()))
        console.printed.clear()

        when:
        run('project', 'show', 'widgets')

        then:
        def layout = home.project(name('widgets'))
        def text = console.printed.join()
        text.startsWith("""\
            project widgets: ${layout.dir()}
              project file: ${layout.config()}
              log file: ${layout.logFile('test-instance')}
              serve folder: ${layout.serveDir('test-instance')}
              clone widgets: $widgets (worktrees: ${layout.worktrees(new CloneName('widgets'))})
            configuration:
            """.stripIndent())
        text.contains('  factory.git-network-timeout = 10m  [any]  projects/widgets/project.yaml:4 (overrides 5m from factory.yaml:2)\n')
        text.contains('  factory.docker-command-timeout = ')
    }

    // FR4, D9: without a name, show reports the clone the loader resolved
    def "show without a name reports the project of the resolved clone"() {
        given:
        run('project', 'add', 'widgets', "--dir=$widgets")
        beans.registerSingleton('registeredClone', new RegisteredClone(name('widgets'),
                new CloneName('widgets'), widgets, home.project(name('widgets'))))
        console.printed.clear()

        when:
        run('project', 'show')

        then:
        console.printed.join().startsWith("project widgets: ${home.project(name('widgets')).dir()}\n")
    }

    // FR4: show without a name and no resolved clone names the forms that work
    def "show without a name and no resolved clone is a usage error"() {
        when:
        run('project', 'show')

        then:
        def e = thrown(UsageException)
        e.message == "no registered clone was resolved: run 'gnomish project show <name>', or 'gnomish project show --dir=<registered clone>'"
        console.printed.isEmpty()
    }

    // FR4: show of an unregistered name lists the registered ones
    def "show of an unknown project names the registered ones"() {
        given:
        run('project', 'add', 'widgets', "--dir=$widgets")
        run('project', 'add', 'gadgets', "--dir=${gitTree('src/gadgets')}")

        when:
        run('project', 'show', 'nosuch')

        then:
        def e = thrown(UsageException)
        e.message == 'no project named nosuch is registered; registered: gadgets, widgets'
    }

    private void run(String... raw) {
        // A fresh scan per invocation, as each process gets from the configuration loader.
        new ProjectCommand(home, ProjectRegistry.scan(home), testProperties(), environment,
                beans.getBeanProvider(RegisteredClone), console)
                .run(new DefaultApplicationArguments(raw))
    }

    private Path gitTree(String relative) {
        def dir = Files.createDirectories(tmp.resolve(relative))
        Files.createDirectories(dir.resolve('.git'))
        dir
    }

    private static yaml(Path file) {
        new YamlPropertySourceLoader().load(file.toString(), new FileSystemResource(file)).first()
    }

    private static ProjectName name(String value) {
        new ProjectName(value)
    }
}
