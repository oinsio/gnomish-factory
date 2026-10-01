package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import spock.lang.Specification

/**
 * FR1, design D1 of add-project-registry (single-owner row 1): {@code FactoryHome} is the one code
 * that knows where operator state lives. Before it, the home was derived three times — {@code
 * FactoryPaths.underHome(user.home)}, the {@code .gnomish} literal in {@code ObservabilityPaths},
 * and {@code ${user.home}/.gnomish/logs} in {@code logback-spring.xml} — which is how logs stayed
 * host-wide while worktrees were per clone. So no production source or resource outside {@code
 * FactoryHome} reads {@code user.home} or spells the home folder's name {@code ".gnomish"} in code.
 *
 * <p>Comments are not scanned: a javadoc may still say where the home defaults to. {@code
 * test-fixtures} is not production and is not scanned. The scan asserts it reached the whole
 * production tree, every resource that used to derive the home, and every allowlisted file, the
 * shape {@link BaseHeadDefaultBoundarySpec} uses.
 */
class FactoryHomeBoundarySpec extends Specification {

    private static final String FACTORY_HOME =
    'application/src/main/java/com/github/oinsio/gnomish/app/project/FactoryHome.java'

    /** Who may read {@code user.home}: the owner alone. */
    private static final Map<String, String> USER_HOME_READERS = [
        (FACTORY_HOME): 'the owner: the default home is <user.home>/.gnomish',
    ]

    /** Who may spell {@code ".gnomish"}: the owner, and one listed exemption with its reason. */
    private static final Map<String, String> HOME_NAME_SPELLERS = [
        (FACTORY_HOME): 'the owner: the default home is <user.home>/.gnomish',
        'application/src/main/java/com/github/oinsio/gnomish/app/LawBinding.java':
        'LAW_ROOT is the target repository\'s law root, <clone>/.gnomish/ — not the factory home',
    ]

    /** The resource that derived the home before FactoryHome existed (design, single-owner row 1). */
    private static final String LOGBACK = 'bootstrap/src/main/resources/logback-spring.xml'

    // FR1, D1: no production file outside the owner reads user.home or names the home folder
    def "FR1: only FactoryHome reads user.home or spells the home folder's name"() {
        given: 'every production source and resource outside test-fixtures, comments stripped'
        def sources = RepoSourceTree.productionSources {
            !it.startsWith('test-fixtures/')
        }
        def resources = productionResources()
        Map<String, String> code = (sources.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        } + resources.collectEntries {
            [(RepoSourceTree.relative(it)): resourceCode(it)]
        }) as Map<String, String>

        expect: 'the scan reached the whole production tree and the old resource reader'
        sources.size() > RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        code.containsKey(LOGBACK)
        code.keySet().containsAll(HOME_NAME_SPELLERS.keySet())

        and: 'user.home is read by the owner alone'
        filesWhere(code) { String text ->
            readsUserHome(text)
        } == USER_HOME_READERS.keySet().toSorted()

        and: 'the home folder is named by the owner and the listed exemption alone'
        filesWhere(code) { String text ->
            spellsHomeName(text)
        } == HOME_NAME_SPELLERS.keySet().toSorted()
    }

    // Over a clean tree the detectors never fire; a seeded read must be found, a comment must not.
    def "the detectors find a seeded read and leave a commented one alone: #shape"() {
        expect:
        readsUserHome(RepoSourceTree.codeOnly(source)) == readsHome
        spellsHomeName(RepoSourceTree.codeOnly(source)) == namesHome

        where:
        shape | source || readsHome | namesHome
        'a system property read' | 'Path home = Path.of(System.getProperty("user.home"));' || true | false
        'a logback lookup' | '<file>${user.home}/logs/x.log</file>' || true | false
        'the folder name' | 'return root.resolve(".gnomish");' || false | true
        'both at once' | 'Path.of(System.getProperty("user.home"), ".gnomish")' || true | true
        'a trailing comment' | 'Path home = factoryHome.root(); // was user.home/".gnomish"' || false | false
        'a javadoc line' | ' * defaults to {@code <user.home>/.gnomish}' || false | false
        'a longer name' | 'String dir = ".gnomish-cache";' || false | false
    }

    private static boolean readsUserHome(String code) {
        code.contains('user.home')
    }

    private static boolean spellsHomeName(String code) {
        code.contains('".gnomish"')
    }

    private static List<String> filesWhere(Map<String, String> code, Closure<Boolean> detector) {
        code.findAll { String path, String text ->
            detector(text)
        }.keySet().toSorted()
    }

    /** Every file under a {@code src/main/resources} folder outside build output and test-fixtures. */
    private static List<File> productionResources() {
        def root = RepoSourceTree.repoRoot()
        Files.walk(root).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .map { root.relativize(it).toString() }
            .filter {
                it.contains('/src/main/resources/') && !it.startsWith('test-fixtures/')
            }
            .filter {
                !it.substring(0, it.indexOf('/src/main/')).tokenize('/').contains('build')
            }
            .map { root.resolve(it).toFile() }
            .toList()
        }
    }

    /**
     * A resource with its comments removed: XML comments, and {@code #} lines of the properties,
     * YAML and service files; then the line comments {@link RepoSourceTree#codeOnly} knows.
     */
    private static String resourceCode(File file) {
        file.text.replaceAll(/(?s)<!--.*?-->/, '')
                .readLines()
                .findAll { !it.trim().startsWith('#') }
                .collect { RepoSourceTree.codeOnly(it) }
                .join('\n')
    }
}
