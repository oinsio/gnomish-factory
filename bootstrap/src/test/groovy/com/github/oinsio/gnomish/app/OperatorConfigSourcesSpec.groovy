package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.config.OperatorConfigCheck
import com.github.oinsio.gnomish.config.OperatorLogFile
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ByteArrayResource
import spock.lang.Specification
import spock.lang.TempDir
/**
 * {@link OperatorConfigLoader}: the four ordered sources (FR5), one resolution of {@code --dir}
 * handed to the context (FR3, design D9), and each operator file read once (NFR-P1). The level
 * checks are {@code OperatorConfigViolationsSpec}'s.
 *
 * <p>Implements FR3, FR5, NFR-P1, UX2 of add-project-registry.
 */
class OperatorConfigSourcesSpec extends Specification {

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    // FR5: "Project overrides host"
    def "FR5: the project file overrides the host file"() {
        given:
        harness.hostConfig('factory:\n  git-network-timeout: 5m\n')
        harness.projectConfig('widgets', 'factory:\n  git-network-timeout: 10m\n')

        expect:
        harness.load([
            'status',
            "--dir=$widgets".toString()
        ]).get('factory.git-network-timeout') == '10m'
    }

    // FR5: a key only the host file sets reaches the environment
    def "FR5: a key only the host file sets is in effect"() {
        given:
        harness.hostConfig('factory:\n  git-network-timeout: 5m\n')

        expect:
        harness.load([
            'status',
            "--dir=$widgets".toString()
        ]).get('factory.git-network-timeout') == '5m'
    }

    // FR5: "Command line overrides the project for a permitted key"
    def "FR5: the command line overrides the project file, #form"() {
        given:
        harness.projectConfig('widgets', 'factory:\n  serve:\n    slots: 2\n')

        expect:
        harness.load([
            'serve',
            "--dir=$widgets".toString()
        ] + arguments, [:], systemProperties)
        .get('factory.serve.slots') == '4'

        where:
        form | arguments | systemProperties
        'as an option' | ['--factory.serve.slots=4'] | [:]
        'as a system property' | [] | ['factory.serve.slots': '4']
    }

    // NFR-P1: "Configuration assembly reads each file once"
    def "NFR-P1: the host file and every project file are read exactly once"() {
        given: 'a host file and three registered projects'
        harness.hostConfig('factory:\n  git-network-timeout: 5m\n')
        harness.register('gadgets', harness.gitTree('gadgets'))
        harness.register('gizmos', harness.gitTree('gizmos'))
        harness.projectConfig('widgets', 'factory:\n  serve:\n    slots: 2\n')

        when:
        def loaded = harness.load([
            'take',
            "--dir=$widgets".toString()
        ])

        then: 'the project block came from the same read that registered the clone'
        loaded.get('factory.serve.slots') == '2'
        harness.reads.keySet() == ([harness.home.hostConfig()] + [
            'widgets',
            'gadgets',
            'gizmos'
        ].collect {
            projectFile(it)
        }) as Set
        harness.reads.values().every { it == 1 }
    }

    // FR5: the two files sit below the command line and the system properties, project above host;
    // only the internal log-file property (FR11, OperatorLogFileSpec) sits above everything
    def "FR5: the sources are placed below the command line, the project file above the host file"() {
        given:
        harness.hostConfig('factory:\n  git-network-timeout: 5m\n')
        harness.projectConfig('widgets', 'factory:\n  git-network-timeout: 10m\n')

        when:
        def names = harness.load([
            'run',
            "--dir=$widgets".toString()
        ]).environment.propertySources*.name

        then:
        names.take(5) == [
            OperatorLogFile.SOURCE,
            'commandLineArgs',
            'systemProperties',
            OperatorConfigCheck.PROJECT_SOURCE,
            OperatorConfigCheck.HOST_SOURCE
        ]
    }

    // D5, task 4.5: the bundled resources are not a second home for defaults or a way around the loader
    def "D5: no bundled application resource holds a factory key or a config import"() {
        given: 'every production resource folder of the build'
        def roots = Files.walk(RepoSourceTree.repoRoot(), 5).withCloseable { paths ->
            paths.filter {
                Files.isDirectory(it) && it.endsWith('src/main/resources')
            }
            .filter {
                !RepoSourceTree.repoRoot().relativize(it).toString().split('/').any {
                    it in ['build', '.gradle', '.git']
                }
            }
            .toList()
        }
        def files = roots.collectMany { root ->
            Files.list(root).withCloseable {
                it.filter {
                    isApplicationResource(it)
                }.toList()
            }
        }

        expect: 'the scan reached the composition root\'s resources'
        roots.any {
            it.startsWith(RepoSourceTree.repoRoot().resolve('bootstrap'))
        }

        and:
        files.findAll {
            !forbiddenKeys(it.fileName.toString(), Files.readString(it)).isEmpty()
        }.isEmpty()
    }

    // The detector over a planted file: a clean tree never fires it, so this is what proves it would
    def "the resource detector finds #shape"() {
        expect:
        forbiddenKeys(file, text) == found

        where:
        shape | file | text || found
        'a factory key' | 'application.yaml' | 'factory:\n  instance-name: x\n' || ['factory.instance-name']
        'a config import' | 'application-dev.yml' | 'spring:\n  config:\n    import: file:./x.yaml\n' || ['spring.config.import']
        'a properties key' | 'application.properties' | 'factory.serve.slots=2\n' || ['factory.serve.slots']
        'nothing forbidden' | 'application.yaml' | 'logging:\n  level:\n    root: info\n' || []
    }

    private static boolean isApplicationResource(Path file) {
        def name = file.fileName.toString()
        name.startsWith('application') && (name.endsWith('.yaml') || name.endsWith('.yml') || name.endsWith('.properties'))
    }

    private static List<String> forbiddenKeys(String fileName, String text) {
        def resource = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8))
        def loader = fileName.endsWith('.properties') ? new PropertiesPropertySourceLoader() : new YamlPropertySourceLoader()
        loader.load(fileName, resource).collectMany { source ->
            (source as EnumerablePropertySource).propertyNames.findAll {
                it.startsWith('factory.') || it == 'spring.config.import'
            }.toList()
        }
    }

    private Path projectFile(String name) {
        harness.home.projects().resolve(name).resolve('project.yaml')
    }
}
