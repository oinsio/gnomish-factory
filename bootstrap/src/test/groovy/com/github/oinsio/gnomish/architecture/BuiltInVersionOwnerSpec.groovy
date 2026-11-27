package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * The D8 row "The executor a record names" of extract-agent-round-runner (task 7.1a; D12 as amended
 * 2026-11-27, "The version of a built-in"): the describe version of every built-in is one fact,
 * "compiled into this factory", with one owner, {@code BuiltInExecutors.VERSION}. Every built-in's
 * describe and {@code DescribedExecutors} read the constant, so a round line reads
 * {@code claude@built-in} by the same word the describes answer. The gate: the literal
 * {@code "built-in"} (either quote) appears, comments stripped, in {@code BuiltInExecutors.java} only.
 *
 * <p>The scan is asserted to reach every main source tree of the build and each of the seven
 * describes that spelled the word before this task, each of which now reads the constant, so a moved
 * file or a narrowed scan fails here rather than leaving the rule vacuous. A detector feature shows the
 * rule red on a source that spells the word again.
 *
 * <p>NFR-O1, FR14 of extract-agent-round-runner.
 */
class BuiltInVersionOwnerSpec extends Specification {

    static final String OWNER = 'domain/src/main/java/com/github/oinsio/gnomish/domain/pipeline/BuiltInExecutors.java'

    /** The seven describes that spelled the word before task 7.1a; each reads the constant now. */
    static final List<String> FORMER_SPELLERS = [
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/ClaudeEntry.java',
        'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/command/CommandStageExecutor.java',
        'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/ShellExecutor.java',
        'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/FilesExistExecutor.java',
        'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/http/HttpExecutor.java',
        'adapters/scm/src/main/java/com/github/oinsio/gnomish/adapter/scm/actions/GithubActionsExecutor.java',
        'adapters/scm/src/main/java/com/github/oinsio/gnomish/adapter/scm/status/ScmStatusExecutor.java'
    ]

    /**
     * The main source trees of the build when the gate was written: a floor the scan must reach, never
     * the exact set — a new module widens the scan by itself, since the scan walks the whole tree.
     */
    static final List<String> MAIN_TREES = [
        'adapters',
        'adapters/agent',
        'adapters/git',
        'adapters/github',
        'adapters/scm',
        'application',
        'atomicfile',
        'baseref',
        'bootstrap',
        'build-checks/atomic-write-annotations',
        'build-checks/parameter-count-annotations',
        'build-checks/parameter-count-check',
        'build-logic',
        'domain',
        'executor-contract',
        'githubapi',
        'gitobjects',
        'gittransfer',
        'gnomish-plugin-api',
        'gnomish-plugin-api/sample',
        'httpegress',
        'logtext',
        'operatorconfig',
        'operatorevent',
        'pendingkey',
        'sandbox/core',
        'sandbox/docker',
        'subprocess',
        'test-fixtures',
        'untrustedtext'
    ]

    static final Pattern LITERAL = Pattern.compile(/["']built-in["']/)

    @Shared
    Map<String, String> code = RepoSourceTree.productionSources().collectEntries {
        [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
    }

    def "D8: the scan reached every main source tree, the owner and every former speller"() {
        given:
        def trees = code.keySet().collect {
            it.substring(0, it.indexOf('/src/main/'))
        } as Set

        expect:
        code.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        trees.containsAll(MAIN_TREES)
        code.containsKey(OWNER)

        and: 'each former speller reads the constant'
        FORMER_SPELLERS.findAll {
            !code[it]?.contains('BuiltInExecutors.VERSION')
        } == []
    }

    // NFR-O1 of extract-agent-round-runner (D12, "The version of a built-in"): one owner of the word
    def "D8: the literal built-in is spelled in BuiltInExecutors.java only"() {
        expect:
        spellers(code) == [OWNER]
    }

    def "D8: the rule goes red on a source that spells the word again"() {
        given:
        def planted = 'adapters/src/main/java/com/example/Planted.java'
        def source = 'class Planted { /* "built-in" in a comment is no spelling */ String v = "built-in"; }'

        expect:
        spellers([(OWNER): code[OWNER], (planted): RepoSourceTree.stripComments(source)]) == [OWNER, planted].sort()

        and: 'a comment alone is not a spelling'
        spellers([(planted): RepoSourceTree.stripComments('// "built-in"\nclass Planted {}')]) == []
    }

    private static List<String> spellers(Map<String, String> tree) {
        tree.findAll { LITERAL.matcher(it.value).find() }.keySet().sort()
    }
}
