package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.config.OperatorLogFile
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The log file {@link OperatorConfigLoader} publishes for {@code logback-spring.xml} (FR11, UX3,
 * design D1, D6): per project and instance for a command resolved to a registered project, the
 * host file for a project-less one — both from the factory home, and out of every operator
 * setting's reach.
 *
 * <p>Implements FR11, UX3 of add-project-registry.
 */
class OperatorLogFileSpec extends Specification {

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets
    Path gateway

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        gateway = harness.gitTree('gateway')
        harness.register('widgets', widgets)
        harness.register('gateway', gateway)
    }

    // FR11, UX3: manual-run "Two projects keep separate logs"
    def "FR11: take for widgets and serve for gateway log to their own project's file"() {
        when:
        def take = harness.load([
            'take',
            "--dir=$widgets".toString()
        ])
        def serve = harness.load([
            'serve',
            "--dir=$gateway".toString()
        ])

        then:
        take.get(OperatorLogFile.PROPERTY) == logFile('widgets', 'default').toString()
        serve.get(OperatorLogFile.PROPERTY) == logFile('gateway', 'default').toString()
    }

    // FR11: the instance name the configuration sets names the file, from whichever source sets it
    def "FR11: the file is named after the configured instance"() {
        given:
        harness.projectConfig('widgets', 'factory:\n  instance-name: nightly\n')

        expect:
        harness.load([
            'run',
            "--dir=$widgets".toString()
        ]).get(OperatorLogFile.PROPERTY) ==
        logFile('widgets', 'nightly').toString()
    }

    // FR11: project show <name> is scoped to the named project
    def "FR11: project show <name> logs to the named project's file"() {
        expect:
        harness.load(['project', 'show', 'gateway']).get(OperatorLogFile.PROPERTY) ==
        logFile('gateway', 'default').toString()
    }

    // FR11: a command with no project logs to the host file
    def "FR11: #command logs to the host file"() {
        expect:
        harness.load(command).get(OperatorLogFile.PROPERTY) == harness.home.hostLogFile().toString()

        where:
        command << [['project', 'list'], []]
    }

    // FR11, D1: the file is decided by the owner of operator paths, not by a setting
    def "FR11: a command-line value for the internal property does not move the file"() {
        expect:
        harness.load([
            'run',
            "--dir=$widgets".toString(),
            "--${OperatorLogFile.PROPERTY}=${tmp.resolve('elsewhere.log')}".toString()
        ]).get(OperatorLogFile.PROPERTY) == logFile('widgets', 'default').toString()
    }

    // FR11, NFR-O2: an instance name that cannot be a file name stops startup with one report line
    def "FR11: an instance name holding a separator is reported, printed once, and exits 2"() {
        when:
        harness.load([
            'run',
            "--dir=$widgets".toString(),
            '--factory.instance-name=a/b'
        ])

        then:
        def refused = thrown(ConfigurationViolationsException)
        refused.violations().size() == 1
        refused.violations()[0].startsWith('factory.instance-name: ')
        refused.exitCode == 2
        harness.console.printed == [refused.message + '\n']
    }

    private Path logFile(String project, String instance) {
        harness.home.project(new ProjectName(project)).logFile(instance)
    }
}
