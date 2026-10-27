package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import spock.lang.Specification

/**
 * M2 of add-doctor-command (design, the single-owner table and the {@code DoctorBoundarySpec}
 * paragraph after it): every doctor check takes its verdict from the component the startup path
 * decides with, so no source in the doctor package starts a subprocess, reads an environment
 * variable or a system property, parses YAML or JSON, or reads a file itself. A check that did
 * would be a second decision beside the owner's, free to disagree with the refusal it reports on.
 *
 * <p>Comments are stripped before the scan, so a javadoc may still name what the owner does.
 * {@code Runtime.version()} is not a ban: it reads the running JVM, not the host. The scan asserts
 * it reached every {@code .java} file of the package, compared with a direct listing.
 */
class DoctorBoundarySpec extends Specification {

    private static final String DOCTOR_PACKAGE =
    'bootstrap/src/main/java/com/github/oinsio/gnomish/app/doctor/'

    /** The bans of M2, each with a spelling it catches; a match is a finding. */
    private static final Map<String, String> BANNED = [
        'a subprocess builder' : /\bProcessBuilder\b/,
        'a runtime exec' : /\bRuntime\s*\.\s*(getRuntime|exec)\b/,
        'an environment read' : /\bSystem\s*\.\s*getenv\b/,
        'a system property read' : /\bSystem\s*\.\s*getPropert(y|ies)\b/,
        'a Jackson parser' : /\b(ObjectMapper|JsonParser|JsonFactory|JsonMapper|readTree|readValue)\b|\b(com\.fasterxml\.jackson|tools\.jackson)\b/,
        'a YAML parser' : /\b(Yaml|YAMLFactory|YAMLMapper)\b|\borg\.yaml\.snakeyaml\b/,
        'a file read' : /\bFiles\s*\.\s*(read\w*|newBufferedReader|lines|newInputStream)\b/,
    ]

    // M2: the doctor package holds no subprocess, environment, property, parser or file read
    def "M2: no doctor source starts a process, reads the environment, parses or reads a file"() {
        given: 'every source of the doctor package, comments stripped'
        def sources = RepoSourceTree.productionSources {
            it.startsWith(DOCTOR_PACKAGE)
        }
        Map<String, String> code = sources.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        } as Map<String, String>

        expect: 'the scan reached every .java file of the package'
        !code.isEmpty()
        code.keySet().toSorted() == listedPackageSources()

        and: 'no source spells a banned call'
        findings(code) == []
    }

    // The planted read must be found by the same matcher; a commented one must not.
    def "M2: the scan goes red on a planted ban and leaves a commented one alone: #shape"() {
        expect:
        findings(['Planted.java': RepoSourceTree.stripComments(source)]).collect {
            it.ban
        } == bans

        where:
        shape | source || bans
        'a planted getenv' | 'String t = System.getenv("GITHUB_TOKEN");' || ['an environment read']
        'a planted property' | 'String h = System.getProperty("user.home");' || ['a system property read']
        'a process builder' | 'new ProcessBuilder("git", "--version").start();' || ['a subprocess builder']
        'a runtime exec' | 'Runtime.getRuntime().exec("docker info");' || ['a runtime exec']
        'a Jackson read' | 'new ObjectMapper().readTree(text);' || ['a Jackson parser']
        'a SnakeYAML load' | 'new Yaml().load(text);' || ['a YAML parser']
        'a YAML mapper import' | 'import tools.jackson.dataformat.yaml.YAMLMapper;' || [
            'a Jackson parser',
            'a YAML parser'
        ]
        'a file read' | 'String s = Files.readString(path);' || ['a file read']
        'a file stream' | 'Files.lines(path).count();' || ['a file read']
        'the JVM version' | 'String v = "Java " + Runtime.version();' || []
        'a line comment' | 'int x = 1; // was System.getenv("GITHUB_TOKEN")' || []
        'a javadoc' | '/** Never {@code System.getenv}; see ProcessBuilder. */' || []
    }

    private static List<Map<String, String>> findings(Map<String, String> code) {
        code.collectMany { String path, String text ->
            BANNED.findAll { String ban, String pattern ->
                text =~ pattern
            }.collect { String ban, String pattern ->
                [file: path, ban: ban]
            }
        }
    }

    /** The package's {@code .java} files, listed directly rather than through the build-wide tree walk. */
    private static List<String> listedPackageSources() {
        def dir = RepoSourceTree.repoRoot().resolve(DOCTOR_PACKAGE)
        Files.walk(dir).withCloseable { paths ->
            paths.filter {
                Files.isRegularFile(it) && it.toString().endsWith('.java')
            }
            .map { RepoSourceTree.repoRoot().relativize(it).toString() }
            .toList()
            .toSorted()
        }
    }
}
