package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.SpringApplication
import org.springframework.boot.bootstrap.DefaultBootstrapContext
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link OperatorConfigLoader}: {@code --dir} resolved once per process and handed to the context
 * with the home and the registry it was resolved against (FR3, design D9); which commands resolve a
 * project, and which do not (D6); and the refusal of a boot that skipped the command-line
 * registration (D8).
 *
 * <p>Implements FR3, FR4 of add-project-registry.
 */
class OperatorConfigResolutionSpec extends Specification {

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    // FR3, D9: the resolution, the home and the registry reach the context as singletons
    def "FR3: the resolved clone, the home and the registry are handed to the context"() {
        when:
        def context = harness.load([
            'run',
            "--dir=$widgets".toString()
        ]).context

        then:
        context.getBean(RegisteredClone).clonePath() == widgets
        context.getBean(FactoryHome) == harness.home
        context.getBean(ProjectRegistry).projects()*.name()*.value() == ['widgets']
    }

    // FR3: "A symlinked path reaches its registered clone"
    def "FR3: a symlinked --dir resolves the registered clone and its real path"() {
        given:
        def link = Files.createSymbolicLink(tmp.resolve('w'), widgets)

        when:
        def clone = harness.load([
            'status',
            "--dir=$link".toString()
        ]).context.getBean(RegisteredClone)

        then:
        clone.project().value() == 'widgets'
        clone.clonePath() == widgets
    }

    // FR4, D6: project show <name> takes the named project, not the --dir one
    def "FR4: project show <name> loads that project's block and resolves no clone"() {
        given:
        harness.register('gadgets', harness.gitTree('gadgets'))
        harness.projectConfig('gadgets', 'factory:\n  serve:\n    slots: 7\n')

        when:
        def loaded = harness.load(['project', 'show', 'gadgets'])

        then:
        loaded.get('factory.serve.slots') == '7'
        loaded.context.getBeanProvider(RegisteredClone).getIfAvailable() == null
    }

    // D6: project add, project list and the empty command line resolve no project
    def "D6: #command resolves no project and reads only the host file's block"() {
        given:
        harness.hostConfig('factory:\n  instance-name: from-host\n')
        harness.projectConfig('widgets', 'factory:\n  instance-name: from-project\n')

        when:
        def loaded = harness.load(command)

        then:
        loaded.get('factory.instance-name') == 'from-host'
        loaded.context.getBeanProvider(RegisteredClone).getIfAvailable() == null
        loaded.context.getBean(FactoryHome) == harness.home

        where:
        command << [
            [
                'project',
                'add',
                'gadgets',
                '--dir=/elsewhere'
            ],
            ['project', 'list'],
            []
        ]
    }

    // FR3, D9: a bare project show reports the clone --dir resolved
    def "D9: a bare project show resolves its --dir"() {
        expect:
        harness.load([
            'project',
            'show',
            "--dir=$widgets".toString()
        ]).context.getBean(RegisteredClone)
        .project().value() == 'widgets'
    }

    // D8: a context booted around CommandExit's argument registration is refused
    def "D8: a boot with no registered command line is refused"() {
        given:
        def loader = new OperatorConfigLoader(new DefaultBootstrapContext())

        when:
        loader.postProcessEnvironment(new StandardEnvironment(), new SpringApplication())

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains('CommandExit')
    }

    // D6: ordered right after Spring's own configuration data
    def "D6: the loader runs right after ConfigDataEnvironmentPostProcessor"() {
        expect:
        new OperatorConfigLoader(new DefaultBootstrapContext()).order ==
                ConfigDataEnvironmentPostProcessor.ORDER + 1
    }
}
