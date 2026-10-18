package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The {@code git} {@code MutationScopeSource} runs is found on the client's {@code PATH}, never on
 * the {@code PATH} the build daemon started with (design D14 of fix-terminal-receipt-convergence as
 * amended 2026-10-18, task 7.5; NFR-R4 of scope-pit-locally). Under launchd's {@code PATH} the bare
 * name ran Apple's {@code git}, below the 2.51 floor, where the operator's shell has their own.
 *
 * <p>The {@code git} on the scenario's {@code PATH} is the committed stand-in preset
 * {@code record-delegate} — the real {@code git}, each invocation recorded — reached through a
 * per-run link named {@code git} in a directory no daemon was started with.
 */
class MutationScopeGitPathFunctionalSpec extends Specification {

    static final String FOO = 'mod-a/src/main/java/com/x/Foo.java'
    static final Map<String, String> BASELINE = [(FOO): "// ${FOO}\n", 'mod-b/src/main/java/com/y/Qux.java': '// Qux\n']

    @TempDir
    Path projectDir
    @TempDir
    Path home

    MiniScopeRepository repo

    def setup() {
        repo = new MiniScopeRepository(projectDir, home).gitFromPath()
        repo.init(BASELINE, 'mod-b/src/main/java/com/y/Qux.java')
        repo.git('checkout', '-q', '-b', 'feat')
        repo.write(FOO, 'class Foo { int edited }').commit('edit Foo')
    }

    def "NFR-R4: a bare git resolves on the client's PATH, never on the PATH the daemon started with"() {
        given: 'a git only on the client\'s PATH, ahead of the real one it delegates to'
        Path git = Files.createSymbolicLink(Files.createDirectories(home.resolve('client-bin')).resolve('git'), preset())

        when:
        def scope = repo.scopeWith([PATH: "${git.parent}:${System.getenv('PATH')}".toString()])

        then: 'the scope is the branch\'s, and every git process was the one on the client\'s PATH'
        scope.mode == 'BRANCH'
        scope.changedClasses == [':mod-a': ['com.x.Foo']]
        records(git).size() == 3
    }

    def "NFR-R1: a git the client's PATH does not hold widens to the whole tree, with a warning naming the PATH searched"() {
        given:
        String path = Files.createDirectories(home.resolve('empty-bin')).toString()

        when:
        def scope = repo.scopeWith([PATH: path])

        then:
        scope.mode == 'ALL'
        scope.reason.startsWith('no git on the build\'s PATH')
        scope.output.contains("git was not found on the build's PATH (${path})")
    }

    /** The preset's own link onto the committed script, as {@code StandIn} in {@code :test-fixtures} makes it. */
    private Path preset() {
        Files.createSymbolicLink(home.resolve('record-delegate'),
                Path.of(GradleRunnerSupport.requiredProperty('gnomish.standInLibrary')).resolve('stand-in.sh'))
    }

    /** The blocks the per-run link's {@code record} row appended, one per run. */
    private static List<String> records(Path link) {
        Path log = link.resolveSibling("${link.fileName}.log")
        Files.exists(log) ? log.text.split(/(?m)^--$/).findAll { it.contains('argv=') }.toList() : []
    }
}
