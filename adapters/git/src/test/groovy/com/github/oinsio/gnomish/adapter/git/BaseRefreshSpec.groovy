package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR8, FR9 of add-base-ref-resolution (design D11, D11a; ADR 0006): the narrow refresh fetch
 * of a resolved base. Each kind lands where git itself would put it, the commit is always read back
 * from the destination rather than from {@code FETCH_HEAD}, and the two failure classes stay apart
 * — a deterministic refusal parks the task, only an unanswered remote is charged to the daemon.
 */
class BaseRefreshSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()

    private Path origin
    private Path work
    private Path clone

    def setup() {
        work = initWorkingRepo(tempDir, 'work')
        gitOutput(work, 'checkout', '-b', 'main')
        commit(work, 'a.txt', 'one')
        gitOutput(work, 'checkout', '-b', 'develop')
        commit(work, 'b.txt', 'two')
        gitOutput(work, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'tag', '-a', 'v1.0', '-m', 'release')
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', origin.toString())
        assert gitExitCode(work, 'push', 'origin', 'main', 'develop', 'v1.0') == 0
        assert gitExitCode(tempDir, 'clone', origin.toString(), 'clone') == 0
        clone = tempDir.resolve('clone')
    }

    /** Every wait the refresh spent, in order — the budget as elapsed virtual time. */
    private final VirtualSleeper sleeper = new VirtualSleeper(new VirtualClock())

    /**
     * The production budget — {@link GitInfrastructureRetry#DEFAULT_ATTEMPTS} and the production
     * backoff — measured on virtual time, so an outage exhausts the real bound in microseconds.
     */
    private BaseRefresh refresh(GitProcessRunner boundRunner = runner) {
        new BaseRefresh(boundRunner, new GitInfrastructureRetry(sleeper,
                GitInfrastructureRetry.DEFAULT_ATTEMPTS, GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF))
    }

    /** Adds a commit on {@code branch} in the upstream work repo and publishes it to origin. */
    private String advance(String branch, String file) {
        gitOutput(work, 'checkout', branch)
        commit(work, file, 'more')
        assert gitExitCode(work, 'push', 'origin', branch) == 0
        gitOutput(work, 'rev-parse', 'HEAD')
    }

    def "FR6: a branch base is refreshed to origin's current tip"() {
        given: 'origin has moved past what the clone fetched at clone time'
        def advanced = advance('develop', 'c.txt')
        assert gitOutput(clone, 'rev-parse', 'refs/remotes/origin/develop') != advanced

        when:
        def outcome = refresh().refresh(clone, 'develop')

        then: 'the answer is origin\'s tip, and the remote-tracking ref now carries it'
        outcome == new BaseRefreshOutcome.Refreshed('develop', advanced, BaseRefKind.BRANCH)
        gitOutput(clone, 'rev-parse', 'refs/remotes/origin/develop') == advanced
    }

    def "FR6: a tag base lands in refs/tags and reports the commit, not the tag object"() {
        given: 'a clone that does not yet hold the tag'
        assert gitExitCode(clone, 'tag', '-d', 'v1.0') == 0
        def tagged = gitOutput(work, 'rev-parse', 'v1.0^{commit}')

        when:
        def outcome = refresh().refresh(clone, 'v1.0')

        then: 'the annotated tag is peeled, so the branch can start from a commit'
        outcome == new BaseRefreshOutcome.Refreshed('v1.0', tagged, BaseRefKind.TAG)
        gitOutput(clone, 'rev-parse', 'refs/tags/v1.0^{commit}') == tagged

        and: 'no other tag was auto-followed in'
        gitOutput(clone, 'for-each-ref', '--format=%(refname)', 'refs/tags') == 'refs/tags/v1.0'
    }

    def "FR6: a local tag diverging from origin's refuses without moving it, naming both commits"() {
        given: 'the clone holds v1.0 pointing somewhere else entirely'
        def originCommit = gitOutput(clone, 'rev-parse', 'refs/tags/v1.0')
        def elsewhere = gitOutput(clone, 'rev-parse', 'refs/remotes/origin/main')
        assert gitExitCode(clone, 'tag', '-f', 'v1.0', elsewhere) == 0

        when:
        def outcome = refresh().refresh(clone, 'v1.0')

        then: 'a task-level refusal, with both sides of the disagreement in the report'
        outcome instanceof BaseRefreshOutcome.Refused
        def report = (outcome as BaseRefreshOutcome.Refused).report()
        report.contains(elsewhere)
        report.contains(originCommit)

        and: "the operator's tag is exactly where it was"
        gitOutput(clone, 'rev-parse', 'refs/tags/v1.0') == elsewhere
    }

    def "FR8: a commit base the clone already holds costs no network at all"() {
        given:
        Path log = tempDir.resolve('sha-present.log')
        def held = gitOutput(clone, 'rev-parse', 'refs/remotes/origin/develop')

        when:
        def outcome = refresh(new GitProcessRunner(recordingGit(log).toString())).refresh(clone, held)

        then:
        outcome == new BaseRefreshOutcome.Refreshed(held, held, BaseRefKind.COMMIT)

        and: 'neither the refs read nor the fetch was needed — this is the offline --base case'
        !recordedSubcommands(log).contains('fetch')
        !recordedSubcommands(log).contains('ls-remote')
    }

    def "FR8: an abbreviated commit the clone holds resolves to its full name offline"() {
        given:
        def held = gitOutput(clone, 'rev-parse', 'refs/remotes/origin/develop')

        when:
        def outcome = refresh().refresh(clone, held.substring(0, 10))

        then:
        outcome instanceof BaseRefreshOutcome.Refreshed
        (outcome as BaseRefreshOutcome.Refreshed).commit() == held
        (outcome as BaseRefreshOutcome.Refreshed).kind() == BaseRefKind.COMMIT
    }

    def "FR6: a commit base the clone lacks is fetched as objects only, with no ref written"() {
        given: 'a commit published to origin after this clone was made'
        def advanced = advance('develop', 'd.txt')
        assert gitExitCode(clone, 'cat-file', '-e', advanced + '^{commit}') != 0
        def refsBefore = gitOutput(clone, 'for-each-ref', '--format=%(refname)')

        when:
        def outcome = refresh().refresh(clone, advanced)

        then:
        outcome == new BaseRefreshOutcome.Refreshed(advanced, advanced, BaseRefKind.COMMIT)
        gitExitCode(clone, 'cat-file', '-e', advanced + '^{commit}') == 0

        and: 'no ref was created for it, and FETCH_HEAD was never even written'
        gitOutput(clone, 'for-each-ref', '--format=%(refname)') == refsBefore
        !Files.exists(clone.resolve('.git/FETCH_HEAD'))
    }

    def "D11a: a name origin holds as both a branch and a tag parks the task, fetching neither"() {
        given: 'origin carries hotfix in both namespaces'
        def branchCommit = gitOutput(work, 'rev-parse', 'develop')
        def tagCommit = gitOutput(work, 'rev-parse', 'main')
        assert gitExitCode(work, 'push', 'origin', "${branchCommit}:refs/heads/hotfix") == 0
        assert gitExitCode(work, 'push', 'origin', "${tagCommit}:refs/tags/hotfix") == 0
        Path log = tempDir.resolve('collision.log')

        when:
        def outcome = refresh(new GitProcessRunner(recordingGit(log).toString())).refresh(clone, 'hotfix')

        then: 'a refusal naming both commits — git would have silently preferred the tag'
        outcome instanceof BaseRefreshOutcome.Refused
        def report = (outcome as BaseRefreshOutcome.Refused).report()
        report.contains(branchCommit)
        report.contains(tagCommit)

        and: 'no fetch was attempted for either namespace'
        !recordedSubcommands(log).contains('fetch')
    }

    def "FR6: a ref origin holds in neither namespace parks the task"() {
        when:
        def outcome = refresh().refresh(clone, 'no-such-base')

        then:
        outcome instanceof BaseRefreshOutcome.Refused
        (outcome as BaseRefreshOutcome.Refused).report().contains('no-such-base')
    }

    def "FR9: an unreachable origin is unavailable, never a refusal, and no ref moves"() {
        given:
        assert gitExitCode(clone, 'remote', 'set-url', 'origin', tempDir.resolve('gone.git').toString()) == 0
        def refsBefore = gitOutput(clone, 'for-each-ref', '--format=%(refname) %(objectname)')

        when:
        def outcome = refresh().refresh(clone, 'develop')

        then: 'the infrastructure class — the one the daemon is charged for'
        outcome instanceof BaseRefreshOutcome.Unavailable
        !(outcome instanceof BaseRefreshOutcome.Refused)

        and: 'fail-closed: nothing in the clone changed'
        gitOutput(clone, 'for-each-ref', '--format=%(refname) %(objectname)') == refsBefore
    }

    def "FR9: a clone with no origin is a deterministic refusal, not an outage"() {
        given:
        assert gitExitCode(clone, 'remote', 'remove', 'origin') == 0

        when:
        def outcome = refresh().refresh(clone, 'develop')

        then:
        outcome instanceof BaseRefreshOutcome.Refused
        (outcome as BaseRefreshOutcome.Refused).report().contains("no 'origin' remote")
    }

    def "FR9: only the unanswered arm is re-asked, and the budget is bounded"() {
        given: 'a git whose refs read always fails, counting the invocations'
        Path log = tempDir.resolve('ls-remote.log')
        Path gitBinary = tempDir.resolve('failing-git.sh')
        gitBinary.toFile().text = """#!/bin/sh
for a in "\$@"; do
  if [ "\$a" = "ls-remote" ]; then echo "\$@" >> "${log}"; echo "fatal: unable to access origin" >&2; exit 128; fi
done
exec git "\$@"
"""
        gitBinary.toFile().executable = true

        when:
        def outcome = refresh(new GitProcessRunner(gitBinary.toString())).refresh(clone, 'develop')

        then:
        outcome instanceof BaseRefreshOutcome.Unavailable
        Files.readAllLines(log).size() == GitInfrastructureRetry.DEFAULT_ATTEMPTS

        and: 'the waits between them are the production backoff, doubling and bounded'
        sleeper.slept == [
            GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF,
            GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF.multipliedBy(2)
        ]
    }

    def "FR9: a deterministic refusal is answered on the first attempt, never retried"() {
        given:
        Path log = tempDir.resolve('refused.log')

        when:
        def outcome = refresh(new GitProcessRunner(recordingGit(log).toString())).refresh(clone, 'no-such-base')

        then:
        outcome instanceof BaseRefreshOutcome.Refused
        recordedSubcommands(log).count('ls-remote') == 1

        and: 'no infrastructure budget is spent on a settled fact'
        sleeper.slept.isEmpty()
    }

    def "NFR-P1: a refreshed branch costs exactly one refs read and one fetch"() {
        given:
        Path log = tempDir.resolve('cost.log')
        advance('develop', 'e.txt')

        when:
        def outcome = refresh(new GitProcessRunner(recordingGit(log).toString())).refresh(clone, 'develop')

        then:
        outcome instanceof BaseRefreshOutcome.Refreshed
        recordedSubcommands(log).count('ls-remote') == 1
        recordedSubcommands(log).count('fetch') == 1
    }

    def "FR6: an abbreviated commit the clone lacks is asked of origin as a ref name"() {
        given: 'hex, but too short for any remote to be asked for it as an object'
        def unheld = advance('develop', 'abbrev.txt').substring(0, 12)
        assert gitExitCode(clone, 'rev-parse', '--verify', '--quiet', unheld + '^{commit}') != 0
        Path log = tempDir.resolve('abbrev.log')

        when:
        def outcome = refresh(new GitProcessRunner(recordingGit(log).toString())).refresh(clone, unheld)

        then: 'it falls through to the namespaces rather than sending a guess to origin'
        outcome instanceof BaseRefreshOutcome.Refused
        (outcome as BaseRefreshOutcome.Refused).report().contains(unheld)
        recordedSubcommands(log).count('ls-remote') == 1
        !recordedSubcommands(log).contains('fetch')
    }

    def "FR6: a local tag already agreeing with origin's is refreshed, not refused"() {
        given: 'the clone holds v1.0 at exactly the commit origin holds it at'
        def agreed = gitOutput(clone, 'rev-parse', 'refs/tags/v1.0')

        when:
        def outcome = refresh().refresh(clone, 'v1.0')

        then:
        outcome instanceof BaseRefreshOutcome.Refreshed
        (outcome as BaseRefreshOutcome.Refreshed).kind() == BaseRefKind.TAG
        gitOutput(clone, 'rev-parse', 'refs/tags/v1.0') == agreed
    }

    def "FR9: a branch or tag fetch origin refuses while still reachable is a task-level refusal"() {
        given: 'a git that answers every refs read for real but fails every fetch'
        assert gitExitCode(clone, 'tag', '-d', 'v1.0') == 0
        advance('develop', 'stale.txt')

        when:
        def outcome = refresh(new GitProcessRunner(fetchAlwaysFails().toString())).refresh(clone, base)

        then: 'origin still answers the probe, so the non-delivering fetch is a refusal, not an outage'
        outcome instanceof BaseRefreshOutcome.Refused
        (outcome as BaseRefreshOutcome.Refused).report().contains(expectedDetail)
        (outcome as BaseRefreshOutcome.Refused).report().contains('authentication or permission')

        and: 'fail-closed: the stale tracking ref this clone still holds is never reported as fresh'
        !(outcome instanceof BaseRefreshOutcome.Refreshed)

        where:
        base || expectedDetail
        'develop' || 'branch fetch of develop'
        'v1.0' || 'tag fetch of v1.0'
    }

    def "FR9: a branch or tag fetch that delivers nothing against an unreachable origin is infrastructure"() {
        given: 'a git that answers the base refs read but fails every fetch and every probe of HEAD'
        assert gitExitCode(clone, 'tag', '-d', 'v1.0') == 0
        advance('develop', 'stale2.txt')

        when:
        def outcome = refresh(new GitProcessRunner(fetchFailsAndOriginUnreachable().toString())).refresh(clone, base)

        then: 'the same non-delivering fetch, the opposite class — because the probe got no answer either'
        outcome instanceof BaseRefreshOutcome.Unavailable
        !(outcome instanceof BaseRefreshOutcome.Refused)
        !(outcome instanceof BaseRefreshOutcome.Refreshed)

        where:
        base << ['develop', 'v1.0']
    }

    def "FR9: a commit origin is reachable but will not serve is a task-level refusal"() {
        given: 'a commit this clone lacks, and a git whose fetch fails while ls-remote answers'
        def advanced = advance('develop', 'g.txt')
        assert gitExitCode(clone, 'cat-file', '-e', advanced + '^{commit}') != 0

        when:
        def outcome = refresh(new GitProcessRunner(fetchAlwaysFails().toString())).refresh(clone, advanced)

        then: 'the probe separates "declined" from "unreachable" without reading git\'s wording'
        outcome instanceof BaseRefreshOutcome.Refused
        (outcome as BaseRefreshOutcome.Refused).report().contains('uploadpack.allowAnySHA1InWant')
    }

    def "FR9: a commit whose fetch fails against an unreachable origin is infrastructure"() {
        given:
        def advanced = advance('develop', 'h.txt')
        assert gitExitCode(clone, 'cat-file', '-e', advanced + '^{commit}') != 0
        assert gitExitCode(clone, 'remote', 'set-url', 'origin', tempDir.resolve('gone.git').toString()) == 0

        when:
        def outcome = refresh().refresh(clone, advanced)

        then: 'the same failed fetch, the opposite class — because the probe got no answer either'
        outcome instanceof BaseRefreshOutcome.Unavailable
    }

    def "FR6: a fetch that exits zero without delivering the ref is unavailable, not refreshed"() {
        given: 'a branch this clone has no tracking ref for, and a git whose fetch is a silent no-op'
        gitOutput(work, 'checkout', '-b', 'feature/late')
        commit(work, 'late.txt', 'late')
        assert gitExitCode(work, 'push', 'origin', 'feature/late') == 0
        assert gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/feature/late') != 0

        when:
        def outcome = refresh(new GitProcessRunner(fetchSilentlyDoesNothing().toString()))
                .refresh(clone, 'feature/late')

        then: 'the exit code alone is never enough — the destination has to exist'
        outcome instanceof BaseRefreshOutcome.Unavailable
        (outcome as BaseRefreshOutcome.Unavailable).reason().contains('left no refs/remotes/origin/feature/late')
    }

    /** A git whose fetch reports success and does nothing: the "delivered nothing" arm's only cause. */
    private Path fetchSilentlyDoesNothing() {
        Path script = tempDir.resolve('fetch-noop-git.sh')
        script.toFile().text = """#!/bin/sh
for a in "\$@"; do
  case "\$a" in
    fetch) exit 0;;
  esac
done
exec git "\$@"
"""
        script.toFile().executable = true
        script
    }

    /**
     * A git that fails every fetch while passing everything else — refs reads included — to the real
     * binary: the one combination that separates "origin does not carry it" from "this clone could
     * not get it", and one a local bare remote cannot be talked into producing on demand.
     */
    private Path fetchAlwaysFails() {
        Path script = tempDir.resolve('fetch-fails-git.sh')
        script.toFile().text = """#!/bin/sh
for a in "\$@"; do
  case "\$a" in
    fetch) echo 'fatal: unable to access origin' 1>&2; exit 128;;
  esac
done
exec git "\$@"
"""
        script.toFile().executable = true
        script
    }

    /**
     * A git that fails every fetch and, separately, fails the probe's own {@code ls-remote origin
     * HEAD} — while still answering a base refs read (the {@code refs/heads}/{@code refs/tags}
     * pattern {@link RemoteBaseRef} sends) for real. The two {@code ls-remote} questions are told
     * apart by their argument, exactly as {@link OriginProbe} and {@link RemoteBaseRef} differ in
     * production, so this simulates origin going unreachable in the gap between the two calls.
     */
    private Path fetchFailsAndOriginUnreachable() {
        Path script = tempDir.resolve('fetch-fails-origin-gone-git.sh')
        script.toFile().text = """#!/bin/sh
for a in "\$@"; do
  case "\$a" in
    fetch) echo 'fatal: unable to access origin' 1>&2; exit 128;;
    HEAD) echo 'fatal: unable to access origin' 1>&2; exit 128;;
  esac
done
exec git "\$@"
"""
        script.toFile().executable = true
        script
    }

    def "FR6: every refresh fetch carries the flags that keep it narrow"() {
        given:
        Path log = tempDir.resolve("flags-${base}.log")
        advance('develop', "flags-${base}.txt")

        when:
        refresh(new GitProcessRunner(recordingGit(log).toString())).refresh(clone, base)

        then: 'one refspec, no auto-followed tags, no configured refmap, no FETCH_HEAD, full depth'
        def fetchArgv = Files.readAllLines(log).find { it.contains('fetch') }
        fetchArgv.contains('--no-tags')
        fetchArgv.contains('--no-write-fetch-head')
        fetchArgv.contains('--refmap=')
        fetchArgv.contains(refspec)
        !fetchArgv.contains('--depth')

        and: 'only a branch is forced — a tag is fetched under git\'s own create-or-refuse rule'
        fetchArgv.contains('+' + refspec) == forced

        where:
        base | refspec || forced
        'develop' | 'refs/heads/develop:refs/remotes/origin/develop' || true
        'v1.0' | 'refs/tags/v1.0:refs/tags/v1.0' || false
    }
}
