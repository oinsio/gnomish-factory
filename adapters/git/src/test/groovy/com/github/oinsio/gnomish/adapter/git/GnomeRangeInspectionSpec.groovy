package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D18): one inspection of a range of gnome commits,
 * three outcomes. Every refusal row on a real repository — a service subject, a second parent, an
 * owned path, a request under another round's name, a base the top does not descend from — and the
 * branch unchanged after each, since judging moves nothing; a git that fails while producing the
 * evidence is cannot-verify, never clean and never a violation.
 */
class GnomeRangeInspectionSpec extends Specification implements BareGitRepoFixture, FailingSubcommandGitFixture {

    static final String BRANCH = 'gnomish/PROJ-1'
    static final AttemptKey KEY = new AttemptKey('PROJ-1', 'implement', 0)
    static final RoundToken OTHER = RoundToken.of('f' * 40)

    @TempDir
    Path tempDir

    Path repo
    String base

    def setup() {
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'first'
        new File(repo.toFile(), '.gnomish-task').mkdirs()
        new File(repo.toFile(), '.gnomish-task/state.json').text = '{"factory":true}'
        commitAll(repo)
        assert gitExitCode(repo, 'checkout', '-q', '-b', BRANCH) == 0
        base = head()
    }

    private String head() {
        gitOutput(repo, 'rev-parse', 'HEAD')
    }

    private String gnomeCommits(String path, String content = 'work', String subject = 'gnome round') {
        def file = new File(repo.toFile(), path)
        file.parentFile.mkdirs()
        file.text = content
        commitAll(repo, subject)
        head()
    }

    private RangeVerdict inspect(String top, AdmissionRule rule = AdmissionRule.midRound(KEY, RoundToken.of(base))) {
        def before = gitOutput(repo, 'for-each-ref', '--format=%(refname) %(objectname)')
        def verdict = new GnomeRangeInspection(new GitProcessRunner(), repo).inspect(base, top, rule)
        // Judging moves nothing: every ref is where it was.
        assert gitOutput(repo, 'for-each-ref', '--format=%(refname) %(objectname)') == before
        verdict
    }

    def "FR13: ordinary gnome commits above the base are clean"() {
        given:
        gnomeCommits('src/App.java')
        def top = gnomeCommits('src/Other.java')

        expect:
        inspect(top) instanceof RangeVerdict.Clean
    }

    def "FR13: an empty range is clean"() {
        expect:
        inspect(base) instanceof RangeVerdict.Clean
    }

    // Scenario: git/git-task-persistence / A service subject in a gnome commit is a violation
    def "FR13: a gnome commit with a service subject is refused, naming the commit and the subject — #subject"() {
        given:
        def forged = gnomeCommits('src/App.java', 'work', subject)
        def top = gnomeCommits('src/Other.java')

        when:
        def verdict = inspect(top)

        then:
        verdict instanceof RangeVerdict.Violated
        def reason = (verdict as RangeVerdict.Violated).reason().forLog()
        reason.contains(forged)
        reason.contains(subject)

        where:
        subject << [
            'gnomish: cleanup',
            'gnomish: snapshot implement#0 ' + 'a' * 40,
            'gnomish: round implement#0'
        ]
    }

    def "FR13: a merge commit in the range is refused as a second parent"() {
        given:
        assert gitExitCode(repo, 'checkout', '-q', '-b', 'side', base) == 0
        gnomeCommits('side.txt')
        assert gitExitCode(repo, 'checkout', '-q', BRANCH) == 0
        gnomeCommits('main.txt')
        assert gitExitCode(repo, '-c', 'user.email=g@b.c', '-c', 'user.name=g', 'merge', '-q', '--no-edit', 'side') == 0
        def top = head()

        when:
        def verdict = inspect(top)

        then:
        verdict instanceof RangeVerdict.Violated
        (verdict as RangeVerdict.Violated).reason().forLog().contains("commit ${top} has a second parent")
    }

    // Scenario: git/git-task-persistence / History rewrite aborts
    def "FR13: a top the base is not an ancestor of is refused as a history rewrite"() {
        given:
        assert gitExitCode(repo, 'checkout', '-q', '--orphan', 'rewritten') == 0
        def top = gnomeCommits('rewritten.txt')

        when:
        def verdict = inspect(top)

        then:
        verdict instanceof RangeVerdict.Violated
        (verdict as RangeVerdict.Violated).reason().forLog().contains('history rewrite')
    }

    def "FR13: a gnome commit changing a factory-owned path is refused, naming the commit and the path — #path"() {
        given:
        def forged = gnomeCommits(path, '{"outcome":"completed"}')

        when:
        def verdict = inspect(forged)

        then:
        verdict instanceof RangeVerdict.Violated
        def reason = (verdict as RangeVerdict.Violated).reason().forLog()
        reason.contains(forged)
        reason.contains(path)

        where:
        path << [
            '.gnomish-task/state.json',
            '.gnomish-task/task.json',
            '.gnomish-task/attempts/x/0/trace.jsonl'
        ]
    }

    // Scenario: git/git-task-persistence / Decision request is the one permitted state-directory write
    def "FR16: the round's own decision request is the one permitted state-directory write"() {
        given:
        def top = gnomeCommits(GnomeRangeInspection.decisionPath(KEY, RoundToken.of(base)), '{"question":"which db?"}')

        expect:
        inspect(top) instanceof RangeVerdict.Clean
    }

    // Scenario: git/git-task-persistence / A request under another round's name is a violation
    def "FR16: a decision request under another round's name is a violation: #description"() {
        given:
        def top = gnomeCommits(path, '{"question":"which db?"}')

        when:
        def verdict = inspect(top)

        then:
        verdict instanceof RangeVerdict.Violated
        (verdict as RangeVerdict.Violated).reason().forLog().contains(path)

        where:
        description | path
        'another round\'s token' | GnomeRangeInspection.decisionPath(KEY, OTHER)
        'the token-less name' | '.gnomish-task/decisions/implement-a0.json'
    }

    def "FR16: decisionPath spells the stage, the attempt and the round token"() {
        expect:
        GnomeRangeInspection.decisionPath(KEY, OTHER) ==
                '.gnomish-task/decisions/implement-a0-' + 'f' * 40 + '.json'
    }

    def "FR13: the factory's own closing commit at the top is judged by its own rule"() {
        given: 'a gnome commit, then the factory\'s in-box state commit writing the two files it owns'
        gnomeCommits('src/App.java')
        def token = RoundToken.of(base)
        def trace = '.gnomish-task/attempts/implement/0/trace.jsonl'
        new File(repo.toFile(), trace).with {
            parentFile.mkdirs()
            text = '{}'
        }
        def top = gnomeCommits('.gnomish-task/state.json', '{"round":0}', ServiceCommitMessages.round('implement', 0))

        expect:
        inspect(top, AdmissionRule.state(KEY, token, trace)) instanceof RangeVerdict.Clean
    }

    def "FR13: a closing top that is not the factory's commit, or writes beyond its files, is refused — #shape"() {
        given:
        def top = gnomeCommits(path, '{"round":0}', subject)

        when:
        def verdict = inspect(top, AdmissionRule.state(KEY, RoundToken.of(base), '.gnomish-task/attempts/implement/0/trace.jsonl'))

        then:
        verdict instanceof RangeVerdict.Violated
        (verdict as RangeVerdict.Violated).reason().forLog().contains(top)

        where:
        shape | path | subject
        'another subject' | '.gnomish-task/state.json' | 'gnome round'
        'a path beyond the two' | '.gnomish-task/task.json' | ServiceCommitMessages.round('implement', 0)
    }

    // Scenario: git/git-task-persistence / A failed boundary probe never passes as clean
    // Scenario: git/git-task-persistence / Both boundary media report cannot-verify
    def "FR13: a git that fails while producing the evidence is cannot-verify — #subcommand"() {
        given:
        def top = gnomeCommits('.gnomish-task/state.json', '{"tampered":true}')
        def git = new GitProcessRunner(gitFailingOn(tempDir, subcommand).toString())

        when:
        def verdict = new GnomeRangeInspection(git, repo).inspect(base, top, AdmissionRule.hostRound())

        then: 'never clean, and the tamper is not attributed to the gnome on a read that did not answer'
        verdict instanceof RangeVerdict.CannotVerify
        (verdict as RangeVerdict.CannotVerify).evidence().forLog().contains(GIT_FAILURE_STDERR)
        gitOutput(repo, 'rev-parse', BRANCH) == top

        where:
        subcommand << ['merge-base', 'log']
    }

    def "FR13: a path listing that does not answer is cannot-verify, after the subjects passed"() {
        given:
        def git = new ScriptedGit()
                .answer(['merge-base'], 0)
                .answer([
                    'log',
                    '--no-color',
                    '--format=*'
                ], 0, "${'b' * 40}\u001f${'a' * 40}\u001fgnome round\n")
                .answer([
                    'log',
                    '--no-color',
                    '--no-renames'
                ], 128, '', 'fatal: bad object\n')

        when:
        def verdict = new GnomeRangeInspection(git, repo).inspect('a' * 40, 'b' * 40, AdmissionRule.hostRound())

        then:
        verdict instanceof RangeVerdict.CannotVerify
        (verdict as RangeVerdict.CannotVerify).evidence().forLog().contains('bad object')
    }
}
