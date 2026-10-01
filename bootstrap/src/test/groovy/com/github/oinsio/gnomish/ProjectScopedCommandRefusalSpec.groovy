package com.github.oinsio.gnomish

import com.github.oinsio.gnomish.app.ConfigurationViolationsException
import com.github.oinsio.gnomish.app.OperatorHomeFixture
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, UX2, design D9 of add-project-registry (project-registry, "Unregistered clone is refused
 * with the fix"): every project-scoped command — the seven, and a bare {@code project show} — works
 * only in a registered clone. The directory is resolved once, by the configuration loader, before
 * the context exists; an unregistered {@code --dir} is refused there with exit code 2 and the
 * {@code project add} line that fixes it, so the command never runs and leaves nothing behind. The
 * commands themselves read the resolved clone ({@code ProjectScope}); their specs run against a
 * registered fixture.
 *
 * <p>Booted through {@link FactoryBoot}, the argument registration the packaged jar goes through.
 *
 * <p>Implements FR3, UX2 of add-project-registry.
 */
class ProjectScopedCommandRefusalSpec extends Specification {

    @TempDir
    Path tmp

    OperatorHomeFixture operatorHome

    def setup() {
        operatorHome = OperatorHomeFixture.install(tmp.resolve('home'))
        operatorHome.register('widgets', gitTree('widgets'))
    }

    def cleanup() {
        operatorHome.close()
    }

    def "FR3, UX2: #command refuses an unregistered --dir before it runs, naming the fix"() {
        given: 'a git working tree no project names'
        def unregistered = gitTree('newproj')
        def homeBefore = listing(operatorHome.home.root())
        String[] commandLine = (command + [
            "--dir=$unregistered".toString()
        ]) as String[]

        when:
        FactoryBoot.boot([:], commandLine).close()

        then: 'startup stops with the configuration exit code and the project add line'
        def refused = thrown(ConfigurationViolationsException)
        refused.exitCode == 2
        refused.violations().size() == 1
        refused.violations()[0].contains("gnomish project add newproj --dir=$unregistered")

        and: 'the command never ran: nothing was written to the home or the clone'
        listing(operatorHome.home.root()) == homeBefore
        listing(unregistered) == ['.git']

        where:
        command << [
            [
                'run',
                '--task=fix the flaky spec'
            ],
            ['take'],
            ['serve'],
            ['status'],
            ['usage', 'task-1'],
            ['board'],
            ['dashboard'],
            ['project', 'show'],
        ]
    }

    private Path gitTree(String name) {
        def dir = Files.createDirectories(tmp.resolve('clones').resolve(name))
        Files.createDirectories(dir.resolve('.git'))
        dir
    }

    private static List<String> listing(Path root) {
        Files.walk(root).withCloseable { paths ->
            paths.filter {
                it != root
            }.map {
                root.relativize(it).toString()
            }.sorted().toList()
        }
    }
}
