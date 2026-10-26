package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.config.OperatorLogFile
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link OperatorConfigLoader}'s collect mode for {@code doctor} (FR7, design D1, D2 of
 * add-doctor-command): every violation the refusal is built on is published as the {@link
 * ConfigurationViolations} singleton instead of thrown, and the rest of the configuration is
 * assembled as for any command, so doctor's remaining checks run over it. The refusal and the
 * listing agree line for line in {@code OperatorConfigSourcesSpec}.
 *
 * <p>Implements FR7 of add-doctor-command.
 */
class OperatorConfigCollectModeSpec extends Specification {

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    // FR7: a clean configuration publishes no violation and is in effect, the clone resolved
    def "FR7: doctor on a clean registered clone publishes no violation and assembles the configuration"() {
        given:
        harness.projectConfig('widgets', 'factory:\n  serve:\n    slots: 2\n')

        when:
        def loaded = harness.load([
            'doctor',
            "--dir=$widgets".toString()
        ])

        then:
        violations(loaded).isEmpty()
        loaded.get('factory.serve.slots') == '2'
        loaded.context.getBean(RegisteredClone).project() == new ProjectName('widgets')
        loaded.get(OperatorLogFile.PROPERTY) ==
                harness.home.project(new ProjectName('widgets')).logFile('default').toString()
    }

    // FR7: the configuration with a violation is still placed, so the remaining checks run over it
    def "FR7: doctor publishes a violation and still places every source"() {
        given:
        harness.hostConfig('factory:\n  git-netwrok-timeout: 5m\n  git-network-timeout: 7m\n')

        when:
        def loaded = harness.load([
            'doctor',
            "--dir=$widgets".toString()
        ])

        then:
        violations(loaded).size() == 1
        violations(loaded)[0].startsWith('factory.git-netwrok-timeout is not a known key')
        loaded.get('factory.git-network-timeout') == '7m'
    }

    // FR7: the instance name the log file needs is one more violation, with the host log in its place
    def "FR7: doctor publishes an instance name that cannot name a file and logs to the host file"() {
        when:
        def loaded = harness.load([
            'doctor',
            "--dir=$widgets".toString(),
            '--factory.instance-name=a/b'
        ])

        then:
        violations(loaded).size() == 1
        violations(loaded)[0].startsWith('factory.instance-name: ')
        loaded.get(OperatorLogFile.PROPERTY) == harness.home.hostLogFile().toString()
        harness.console.printed.isEmpty()
    }

    // FR2, FR7: a directory no project registers leaves doctor project-less, not refused
    def "FR7: doctor in an unregistered directory publishes no violation and resolves no clone"() {
        when:
        def loaded = harness.load([
            'doctor',
            "--dir=${harness.gitTree('elsewhere')}".toString()
        ])

        then:
        violations(loaded).isEmpty()
        loaded.context.getBeansOfType(RegisteredClone).isEmpty()
        loaded.get(OperatorLogFile.PROPERTY) == harness.home.hostLogFile().toString()
    }

    // FR7: what a refusing command reports as a refusal, doctor publishes — the registry it could
    // not read is no bean
    def "FR7: doctor publishes #problem"() {
        given:
        if (problem == 'an unreadable project file') {
            harness.projectConfig('widgets', 'factory: [unclosed\n')
        }

        when:
        def loaded = harness.load([
            'doctor',
            emptyDir ? '--dir=' : "--dir=$widgets".toString()
        ])

        then:
        violations(loaded).size() == 1
        violations(loaded)[0].contains(expected)
        loaded.context.getBeansOfType(RegisteredClone).isEmpty()
        loaded.context.containsBean('projectRegistry') == registry

        where:
        problem | emptyDir || expected | registry
        'an empty --dir' | true || '--dir requires a value' | true
        'an unreadable project file' | false || 'is not valid YAML' | false
    }

    // FR7: every other command keeps today's behaviour — nothing is published beside the refusal
    def "FR7: #command publishes no violations bean"() {
        expect:
        !harness.load([
            command,
            "--dir=$widgets".toString()
        ]).context.containsBean(OperatorConfigLoader.VIOLATIONS_BEAN)

        where:
        command << [
            'run',
            'take',
            'serve',
            'status'
        ]
    }

    // FR7: the empty command line, a test context's boot, is no doctor
    def "FR7: the empty command line publishes no violations bean"() {
        expect:
        !harness.load([]).context.containsBean(OperatorConfigLoader.VIOLATIONS_BEAN)
    }

    private static List<String> violations(OperatorConfigLoaderHarness.Loaded loaded) {
        loaded.context.getBean(OperatorConfigLoader.VIOLATIONS_BEAN, ConfigurationViolations).lines()
    }
}
