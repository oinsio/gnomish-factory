package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.util.function.Predicate
import spock.lang.Shared
import spock.lang.Specification

/**
 * The {@code AgentDialect} row of design D8 of extract-agent-round-runner (M2, task 2.6): Claude's
 * vocabulary lives in the Claude dialect package, {@code adapter.agent.claude}, and nowhere else.
 * Two halves:
 * <ul>
 *   <li>a text scan of every production {@code .java} source, comments included (M2 is a grep, and
 *       a javadoc that teaches {@code stream-json} outside the dialect is the vocabulary leaking),
 *       for {@code stream-json}, {@code ANTHROPIC_}, {@code acceptEdits} and {@code dontAsk};
 *   <li>an ArchUnit rule over the production bytecode: no class outside the dialect package depends
 *       on a class in it, except the composition sites listed in {@link #WIRING}.
 * </ul>
 The old round path's transitional exemptions left with it (tasks 4.1 and 5.1): no file outside the
 * dialect spells the vocabulary, and every listed wiring class is asserted reached and still depending
 * on the dialect, so an entry cannot outlive its reason.
 *
 * <p>M2, FR3 of extract-agent-round-runner.
 */
class ClaudeVocabularyBoundarySpec extends Specification {

    static final List<String> VOCABULARY = [
        'stream-json',
        'ANTHROPIC_',
        'acceptEdits',
        'dontAsk'
    ]

    private static final String PKG = 'com.github.oinsio.gnomish.adapter.agent'
    private static final String DIALECT_PKG = PKG + '.claude'
    private static final String AGENT_DIR = 'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/'
    private static final String DIALECT_DIR = AGENT_DIR + 'claude/'

    /** Composition sites: they bind the dialect where the factory is assembled, and decide nothing of it. */
    static final Map<String, String> WIRING = [
        'com.github.oinsio.gnomish.app.ManualRunConfiguration' : 'the composition root binds the dialect\'s settings judge (ClaudeSettings::validate)',
        'com.github.oinsio.gnomish.adapter.agent.AgentCredentials': 'the doctor\'s agent-credential check reads the dialect\'s credential set (ClaudeEnvironment.SECRETS), which the dialect alone spells',
        'com.github.oinsio.gnomish.app.ClaudeEntry' : 'the claude registry entry builds the dialect over the run\'s binary and allowlist (ClaudeDialect.of; task 4.0a)',
    ]

    /** Dependency exemptions: the wiring sites, by fully qualified name. */
    static final Map<String, String> DEPENDENCY_ALLOWED = WIRING

    @Shared
    List<File> javaSources = RepoSourceTree.productionSources {
        it.endsWith('.java')
    }

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .withImportOption({ location ->
        !location.contains('/test-fixtures/')
    } as ImportOption)
    .importPackages('com.github.oinsio.gnomish')

    def "M2: the scans reached the tree, the dialect and every listed file and class"() {
        given:
        def paths = javaSources.collect { RepoSourceTree.relative(it) }

        expect: 'the text scan reached the production tree and the dialect itself'
        javaSources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        paths.count { it.startsWith(DIALECT_DIR) } > 0

        and: 'the bytecode import reached the dialect and every allowlisted class'
        productionClasses.any { it.packageName == DIALECT_PKG }
        DEPENDENCY_ALLOWED.keySet().findAll {
            !productionClasses.contain(it)
        }.toList() == []
    }

    // Scenario: executor/claude-dialect / No source names the credentials but the dialect
    def "M2: Claude's vocabulary is spelled only in the dialect package"() {
        given:
        def spellers = javaSources.findAll { file ->
            !RepoSourceTree.relative(file).startsWith(DIALECT_DIR) && spells(file.text)
        }.collect { RepoSourceTree.relative(it) }.sort()

        expect: 'no file outside the dialect spells it'
        spellers == []

        and: 'the dialect spells it, so the scan is not vacuous'
        javaSources.any {
            RepoSourceTree.relative(it).startsWith(DIALECT_DIR) && it.text.contains('ANTHROPIC_')
        }
    }

    def "M2, D8: no class outside the dialect package depends on it but the wiring sites"() {
        expect:
        noClasses().that().resideOutsideOfPackage(DIALECT_PKG + '..')
                .and(DescribedPredicate.describe('not an allowlisted class', { JavaClass c ->
                    !DEPENDENCY_ALLOWED.containsKey(topLevel(c))
                } as Predicate<JavaClass>))
                .should().dependOnClassesThat().resideInAPackage(DIALECT_PKG + '..')
                .check(productionClasses)

        and: 'every allowlisted class still depends on the dialect, so a stale entry fails'
        DEPENDENCY_ALLOWED.keySet().findAll {
            !dependsOnDialect(it)
        }.toList() == []
    }

    private static boolean spells(String text) {
        VOCABULARY.any { text.contains(it) }
    }

    private static String topLevel(JavaClass c) {
        c.name.contains('$') ? c.name.substring(0, c.name.indexOf('$')) : c.name
    }

    private boolean dependsOnDialect(String name) {
        productionClasses.findAll { topLevel(it) == name }.any { c ->
            c.directDependenciesFromSelf.any {
                it.targetClass.packageName == DIALECT_PKG || it.targetClass.packageName.startsWith(DIALECT_PKG + '.')
            }
        }
    }
}
