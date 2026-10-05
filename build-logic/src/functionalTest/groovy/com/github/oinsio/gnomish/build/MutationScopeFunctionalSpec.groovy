package com.github.oinsio.gnomish.build

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code MutationScopeSource} — the scope owner of scope-pit-locally is
 * RUN by a Gradle build over a miniature git repository built per scenario with real {@code git},
 * rather than checked by reading its parsing code. See {@link MiniScopeRepository} for the
 * repository, its own git configuration and the invocation-counting wrapper.
 *
 * <p>Hermetic: the mini build applies no convention plugin and every run is {@code --offline}.
 */
class MutationScopeFunctionalSpec extends Specification {

    static final String FOO = 'mod-a/src/main/java/com/x/Foo.java'
    static final String BAR = 'mod-a/src/main/java/com/x/Bar.java'
    static final String BAZ = 'mod-a/src/main/java/com/x/Baz.java'
    static final String QUX = 'mod-b/src/main/java/com/y/Qux.java'
    static final String SPEC = 'mod-a/src/test/groovy/com/x/FooSpec.groovy'
    static final String FIXTURE = 'test-fixtures/src/main/groovy/com/f/Fake.groovy'
    static final Map<String, String> BASELINE = [FOO, BAR, QUX, SPEC, FIXTURE].collectEntries { [(it): "// ${it}\n"] }

    @TempDir
    Path projectDir
    @TempDir
    Path home

    MiniScopeRepository repo

    def setup() {
        repo = new MiniScopeRepository(projectDir, home)
    }

    def "FR1: a production class committed on the branch is the scope, against the merge base"() {
        given:
        branched().write(FOO, 'class Foo { int edited }').commit('edit Foo')

        when:
        def scope = repo.scope()

        then:
        scope.mode == 'BRANCH'
        scope.base == repo.git('rev-parse', 'main')
        scope.baseOrigin == 'merge-base main'
        scope.changedClasses == [':mod-a': ['com.x.Foo']]
        scope.widened == [:]
    }

    def "FR1, D2: staged, unstaged and untracked work is in the scope without any commit"() {
        given:
        branched().write(FOO, 'class Foo { int unstaged }').write(BAZ).write(QUX, 'class Qux { int staged }')
        repo.git('add', QUX)

        expect:
        repo.scope().changedClasses == [':mod-a': ['com.x.Baz', 'com.x.Foo'], ':mod-b': ['com.y.Qux']]
    }

    def "FR2: a class changed only on main after the fork is not scoped"() {
        given:
        branched().write(FOO, 'class Foo { int branch }').commit('branch edit')
        repo.git('checkout', '-q', 'main')
        repo.write(BAR, 'class Bar { int main }').commit('main moved')
        repo.git('checkout', '-q', 'feat')

        expect:
        repo.scope().changedClasses == [':mod-a': ['com.x.Foo']]
    }

    def "FR2, M4: on main itself the first parent is the base, so the merge commit's own change is mutated"() {
        given:
        repo.init(BASELINE, BAR).write(FOO, 'class Foo { int squashed }').commit('squash merge')

        when:
        def scope = repo.scope()

        then:
        scope.base == repo.git('rev-parse', 'HEAD^')
        scope.baseOrigin == 'first parent of HEAD on main'
        scope.changedClasses == [':mod-a': ['com.x.Foo']]
    }

    def "FR2, D3: a fresh branch with no commits of its own scopes only its uncommitted work"() {
        given: "a branch at main's tip, whose last commit edited Bar, with Foo edited in the tree"
        branched().write(FOO, 'class Foo { int unstaged }')

        when:
        def scope = repo.scope()

        then: "the base is HEAD, so main's last commit is not re-mutated"
        scope.base == repo.git('rev-parse', 'HEAD')
        scope.baseOrigin == 'merge-base main'
        scope.changedClasses == [':mod-a': ['com.x.Foo']]
    }

    def "FR2, NFR-R1: no resolvable base widens to the whole tree with the reason (#situation)"() {
        given:
        arrange(repo)

        when:
        def scope = repo.scope()

        then:
        scope.mode == 'ALL'
        scope.reason.startsWith('no scope base:')

        where:
        situation               | arrange
        'no main or origin/main' | { MiniScopeRepository r -> r.init(BASELINE, BAR).git('branch', '-m', 'main', 'trunk') }
        'a root commit on main' | { MiniScopeRepository r -> r.init(BASELINE) }
        'not a git checkout'    | { MiniScopeRepository r -> r.write(FOO) }
    }

    def "FR3, M3: a test change widens its module, a shared fixture change widens every module (#situation)"() {
        given:
        branched()
        change(repo)
        repo.commit(situation)

        expect:
        repo.scope().widened == widened

        where:
        situation             | change                                              || widened
        'a spec edited'       | { MiniScopeRepository r -> r.write(SPEC, '// weakened') } || [':mod-a': 'test change']
        'a spec deleted'      | { MiniScopeRepository r -> r.git('rm', '-q', SPEC) }      || [':mod-a': 'test change']
        'a fixture edited'    | { MiniScopeRepository r -> r.write(FIXTURE, '// fake') }  || MiniScopeRepository.MODULES.collectEntries { k, v -> [(k): 'shared fixture'] }
    }

    def "FR3, D2: a path in a nested module belongs to the nested module, not to the one enclosing it (#situation)"() {
        given:
        branched().write(change).commit(situation)

        when:
        def scope = repo.scope()

        then:
        scope.changedClasses == changedClasses
        scope.widened == widened

        where:
        situation               | change                                         || changedClasses                 | widened
        'a nested class edited' | 'mod-a/inner/src/main/java/com/n/Nested.java'  || [':mod-a:inner': ['com.n.Nested']] | [:]
        'a nested spec edited'  | 'mod-a/inner/src/test/groovy/com/n/NSpec.groovy' || [:]                          | [':mod-a:inner': 'test change']
    }

    def "FR3: a deleted production file and a package-info contribute no class"() {
        given:
        branched().write('mod-a/src/main/java/com/x/package-info.java', 'package com.x;')
        repo.git('rm', '-q', BAR)
        repo.commit('delete Bar')

        expect:
        repo.scope().changedClasses == [:]
    }

    def "FR4, D5: the property and the pitestAll task choose the mode (#arguments)"() {
        given:
        branched().write(FOO, 'class Foo { int edited }')

        when:
        def scope = repo.scope(*arguments)

        then:
        scope.mode == mode
        scope.reason == reason
        scope.globs == globs

        and: 'NFR-P1: a requested whole tree or explicit list runs no git at all'
        repo.invocations().isEmpty()

        where:
        arguments                                       || mode       | reason              | globs
        ['printScope', '-PpitScope=all']                || 'ALL'      | '-PpitScope=all'    | []
        ['printScope', '-PpitScope=com.x.Foo, com.y.*'] || 'EXPLICIT' | null                | ['com.x.Foo', 'com.y.*']
        ['pitestAll']                                   || 'ALL'      | 'pitestAll requested' | []
    }

    def "FR4, NFR-R1: a pitScope that is set but names no glob fails the build rather than skipping every module (#value)"() {
        given:
        branched().write(FOO, 'class Foo { int edited }')

        when:
        def result = repo.runner('printScope', "-PpitScope=${value}".toString()).buildAndFail()

        then:
        result.output.contains('pitScope is set but names no class glob')

        where:
        value << ['', ' , ']
    }

    def "NFR-R4: a non-ASCII path is mapped to its class, committed and untracked alike"() {
        given:
        branched().write('mod-a/src/main/java/com/x/Ünïcode.java').commit('non-ASCII')
        repo.write('mod-b/src/main/java/com/y/Ärger.java')

        expect:
        repo.scope().changedClasses == [':mod-a': ['com.x.Ünïcode'], ':mod-b': ['com.y.Ärger']]
    }

    def "NFR-R2: an unchanged tree reuses the cached configuration, an edit re-resolves the scope"() {
        given:
        branched().write(FOO, 'class Foo { int edited }').commit('edit Foo')

        when:
        def first = repo.scope('printScope', '--configuration-cache')
        def second = repo.scope('printScope', '--configuration-cache')

        then:
        second.output.contains('Reusing configuration cache.')
        second.changedClasses == first.changedClasses

        when:
        repo.write(BAR, 'class Bar { int edited }')
        def third = repo.scope('printScope', '--configuration-cache')

        then:
        !third.output.contains('Reusing configuration cache.')
        third.changedClasses == [':mod-a': ['com.x.Bar', 'com.x.Foo']]
    }

    def "NFR-R4, NFR-R1: the operator's global ignore list cannot hide an untracked class"() {
        given: 'a global configuration, switched on after the baseline is committed, that ignores every Java file'
        branched().write(BAZ)
        home.resolve('ignore').text = '*.java\n'
        repo.globalConfig << "[core]\n\texcludesFile = ${home.resolve('ignore')}\n"

        expect: 'the ignore list is live for git itself'
        !repo.git('ls-files', '--others', '--exclude-standard').contains('Baz.java')

        and: 'the scope still holds the class'
        repo.scope().changedClasses == [':mod-a': ['com.x.Baz']]
    }

    def "NFR-R4: a held index lock does not stop the scope, and no git process takes optional locks"() {
        given:
        branched().write(FOO, 'class Foo { int unstaged }')
        Files.createFile(projectDir.resolve('.git/index.lock'))

        expect:
        repo.scope().changedClasses == [':mod-a': ['com.x.Foo']]
        !repo.invocations().isEmpty()
        repo.invocations().every { it.startsWith('0 ') }
    }

    def "NFR-P1: branch mode runs three git processes, four when only origin/main exists (#situation)"() {
        given:
        branched().write(FOO, 'class Foo { int edited }').commit('edit Foo')
        arrange(repo)

        when:
        def scope = repo.scope()

        then:
        scope.baseOrigin == baseOrigin
        repo.invocations().size() == processes

        where:
        situation          | arrange                                                                 || baseOrigin               | processes
        'a local main'     | { MiniScopeRepository r -> }                                            || 'merge-base main'        | 3
        'only origin/main' | { MiniScopeRepository r -> r.git('update-ref', 'refs/remotes/origin/main', 'main'); r.git('branch', '-D', 'main') } || 'merge-base origin/main' | 4
    }

    private MiniScopeRepository branched() {
        repo.init(BASELINE, BAR)
        repo.git('checkout', '-q', '-b', 'feat')
        repo
    }
}
